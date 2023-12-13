package com.morpheusdata.aws.utils

import com.morpheusdata.aws.EC2ProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.App
import com.morpheusdata.model.AppInstance
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.CloudType
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerType
import com.morpheusdata.model.ContainerType
import com.morpheusdata.model.Instance
import com.morpheusdata.model.InstanceScale
import com.morpheusdata.model.InstanceScaleType
import com.morpheusdata.model.InstanceType
import com.morpheusdata.model.Network
import com.morpheusdata.model.NetworkType
import com.morpheusdata.model.OsType
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.Workload
import com.morpheusdata.model.WorkloadType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

import java.awt.Container

@Slf4j
class CloudFormationResourceMappingUtility  {

	static transactional = false

	static resourceTypeCategory = 'aws.cloudFormation'

	def amazonComputeService

	//splits a spec up into multiple resources and matches each up with a resource type
	static ServiceResponse parseResource(String content, Map scriptConfig, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[resources:[]]]
		try {
			//apply the script config
			log.debug("input spec: {}", content)
			def processedSpec = buildSpecVariableScript(content, scriptConfig)
			log.debug("processed spec: {}", processedSpec)
			//now escape the dollars?
			rtn.data.spec = processedSpec
			//convert the content to json
			def resourceConfig = loadYamlOrJsonMap(processedSpec)

			//get the important stuff
			def resourceList = resourceConfig['Resources']
			//iterate and apply the config
			resourceList?.each { key, value ->
				def type = value['Type']
				def typeMatch = findAwsResourceType(type as String, morpheusContext)
				if(typeMatch) {
					//but the response
					def row = [type:typeMatch.type, apiType:typeMatch.apiType, enabled:typeMatch.enabled, 
						spec:processedSpec, specMap:resourceConfig, morpheusType:typeMatch.morpheusType,
						name:key, iacProvider:'cloudFormation', iacType:typeMatch.apiType, resourceSpec:value
					]
					if(typeMatch instanceof AccountResourceType)
						row.resourceType = typeMatch
					log.info("found resource spec: {}", row.resourceSpec)
					rtn.data.resources << row
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("error parsing resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse mapResource(Instance instance, Map resource, Map opts, MorpheusContext morpheusContext) {
		def appConfig = [name:instance.name, createdBy:instance.createdBy, account:instance.account,
						 serverGroup:instance.serverGroup, resourcePool:instance.resourcePool, site:instance.site,
						 plan:instance.plan, layout:instance.layout, instanceId:instance.id, instanceName:instance.name,
						 user:instance.createdBy, refType:'instance', refId:instance.id, refObj:instance,
						 refConfig:instance.getConfigMap(), region: opts.config.regionCode
		]
		appConfig.defaultCloud = opts.cloud
		//map it
		return mapResource(appConfig, resource, opts, morpheusContext)
	}

	//app resource mapping for a list of specs
	static ServiceResponse mapResource(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[resource:null]]
		try {
			log.debug("map resource: {}", resource)
			//parse the spec
			//format: [type, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType]
			//matching output - state, stateMap
			def buildResults = buildAccountResource(appConfig, resource, opts)
			log.debug("buildResults: {}", buildResults)
			if(buildResults.success == true) {
				def mapResult = buildResults.data
				if(mapResult) {
					resource.key = mapResult.key ?: mapResult.name
					rtn.data.resource = [input:resource, mapping:mapResult]
				}
				if(resource.morpheusType && resource.morpheusType != 'accountResource') {
					//set the results on the input to map a morpheus type
					resource.accountResource = mapResult
					//build the morpheus resource - switch on case
					def morpheusResults
					switch(resource.morpheusType) {
						case 'instance':
							log.debug("mapping instance: {}", appConfig.refType)
							if(appConfig.refType == 'instance') { //if this is an instance - create a container to add to it
								resource.morpheusType = 'container'
								morpheusResults = mapContainer(appConfig, resource, opts, morpheusContext)
							} else if(appConfig.refType == 'app')  { //if this is an app - create an instance
								morpheusResults = mapInstance(appConfig, resource, opts, morpheusContext)
							}
							break
						default:
							break
					}
					//add to response
					if(morpheusResults) {
						rtn.data.resource.morpheusResource = morpheusResults.data.resource
					}
					//done
				}
				rtn.success = true
			}
		} catch(e) {
			rtn.msg = "error parsing cf resource: ${e}".toString()
			log.error(rtn.msg, e)
		}
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse resolveResource(Instance instance, Map resource, Collection resourceResults, Map opts, MorpheusContext morpheusContext) {
		def appConfig = [name:instance.name, createdBy:instance.createdBy, account:instance.account,
						 serverGroup:instance.serverGroup, resourcePool:instance.resourcePool, site:instance.site,
						 plan:instance.plan, layout:instance.layout, instanceId:instance.id, instanceName:instance.name,
						 user:instance.createdBy, refType:'instance', refId:instance.id, refObj:instance,
						 refConfig:instance.getConfigMap()
		]
		return resolveResource(appConfig, resource, resourceResults, opts, morpheusContext)
	}

	static ServiceResponse resolveResource(App app, Map resource, Collection resourceResults, Map opts, MorpheusContext morpheusContext) {
		def appConfig = [name:app.name, createdBy:app.createdBy, account:app.account,
						 serverGroup:app.serverGroup, resourcePool:app.resourcePool, site:app.site,
						 appId:app.id, appName:app.name, user:app.createdBy, refType:'app', refId:app.id, refObj:app,
						 refConfig:app.getConfigMap()
		]
		return resolveResource(appConfig, resource, resourceResults, opts, morpheusContext)
	}

	//adds provision info to resources
	static ServiceResponse resolveResource(Map appConfig, Map resource, Collection resourceResults, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[:]]
		//resource: [id, morpheusType:'accountResource', type, name, resource]
		try {
			def resourceName = resource.name
			def matchResource = resourceResults.find {
				it.getLogicalResourceId() == resourceName
			}
			if(matchResource) {
				rtn.data.externalId = matchResource.getPhysicalResourceId()
				rtn.data.internalId = matchResource.getLogicalResourceId()
				rtn.data.found = true
				rtn.data.resourceState = matchResource
				rtn.success = true
				resource.resource.externalId = rtn.data.externalId
				resource.resource.internalId = rtn.data.internalId
				morpheusContext.async.cloud.resource.save(resource.resource as AccountResource).blockingGet()
				if(resource.resource.refType && resource.resource.refId) {
					switch(resource.resource.refType) {
						case 'instance':
							if(resource.resource.refId && resource.resource.externalId)
								appConfig.refObj.externalId = resource.resource.externalId
							break
						case 'container':
							resolveContainer(appConfig, resource, matchResource, opts, morpheusContext)
							break
						default:
							break
					}
				}
			}
		} catch(e) {
			log.error("error resolving resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse resolveContainer(Map appConfig, Map resource, Object resourceResult, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[:]]
		//resource: [id, morpheusType:'accountResource', type, name, resource]
		try {
			if(resource.resource.refId && resource.resource.externalId) {
				def workload = morpheusContext.async.workload.get(resource.resource.refId?.toLong()).blockingGet()
				if(workload) {
					workload.externalId = resource.resource.externalId
					if(workload.server) {
						workload.server.externalId = resource.resource.externalId

						// Fetch the ec2 instance and see if the tag name is set
						def serverResults = AmazonComputeUtility.getServerDetail([amazonClient: opts.amazonClient, serverId: resource.resource.externalId])
						def nameTag = serverResults.success && serverResults.server ? serverResults.server.getTags()?.find { it.getKey()?.toLowerCase() == 'name' }?.getValue() : null
						if(nameTag && workload.server.name != nameTag) {
							resource.resource.name = nameTag
							morpheusContext.async.cloud.resource.save(resource.resource as AccountResource).blockingGet()
							workload.server.name = nameTag
							morpheusContext.async.computeServer.save(workload.server).blockingGet()
						}
					}
					morpheusContext.async.workload.save(workload).blockingGet()
				}
			}
		} catch(e) {
			log.error("error resolving container: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	//set details from the cloud obj to the resources
	static ServiceResponse updateResource(Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[:]]
		//resource: [id, morpheusType:'accountResource', type, name, resource, state]
		try {
			//get the status
			def doSave = false
			def resourceStatus = decodeResourceStatus(resource.state?.getResourceStatus())
			def resourceReason = resource.state?.getResourceStatusReason()
			if(resource.resource.status != resourceStatus) {
				resource.resource.status = resourceStatus
				doSave = true
			}
			if(resource.resource.statusMessage != resourceReason) {
				resource.resource.statusMessage = resourceReason
				doSave = true
			}
			if(doSave == true) {
				resource.resource.statusDate = new Date()
				morpheusContext.async.cloud.resource.save(resource.resource as AccountResource).blockingGet()
				rtn.success = true
			}
		} catch(e) {
			log.error("error updating resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse updateResource(AccountResource resource, cloudResource, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[:]]
		try {
			//get the status
			def doSave = false
			def resourceStatus = decodeResourceStatus(cloudResource.resourceStatus)
			def resourceReason = cloudResource.resourceStatusReason
			if(resource.status != resourceStatus) {
				resource.status = resourceStatus
				doSave = true
			}
			if(resource.statusMessage != resourceReason) {
				resource.statusMessage = resourceReason
				doSave = true
			}
			if(doSave == true) {
				resource.statusDate = new Date()

				if(resource.id) {
					rtn.data.resource = morpheusContext.services.cloud.resource.save(resource)
				} else {
					rtn.data.resource = morpheusContext.services.cloud.resource.create(resource)
				}
				rtn.success = true
			}
		} catch(e) {
			log.error("error updating resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	//creating
	static ServiceResponse createResource(Instance instance, Map resource, Map opts, MorpheusContext morpheusContext) {
		//resource [input:[type, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType],
		//  mapping[:], spec:ResourceSpec]
		def rtn = [success:false, data:resource]
		rtn.data.newResource = false
		rtn.data.userData = resource.mapping?.config?.userData ?: [found:false]
		//configure it from the mapping
		def configResults = configureResource(instance, resource, opts, morpheusContext)
		log.debug("config results: {}", configResults)
		if(configResults.success == true && configResults.data) {
			rtn.data += configResults.data
			rtn.success = true
		}
		//save
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse createResource(App app, Map resource, Map opts, MorpheusContext morpheusContext) {
		//resource [input:[type, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType],
		//  mapping[:], spec:ResourceSpec]
		def rtn = [success:false, data:resource]
		rtn.data.newResource = false
		rtn.data.userData = resource.mapping?.config?.userData ?: [found:false]
		//configure it from the mapping
		def configResults = configureResource(app, resource, opts, morpheusContext)
		log.debug("config results: {}", configResults)
		if(configResults.success == true && configResults.data) {
			rtn.data += configResults.data
			rtn.success = true
		}
		//save
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse configureResource(Instance instance, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		def appConfig = [name:instance.name, createdBy:instance.createdBy, account:instance.account,
						 serverGroup:instance.serverGroup, resourcePool:instance.resourcePool, site:instance.site,
						 plan:instance.plan, layout:instance.layout, instanceId:instance.id, instanceName:instance.name,
						 user:instance.createdBy, refType:'instance', refId:instance.id, refObj:instance,
						 refConfig:instance.getConfigMap()
		]
		//build an iacId
		appConfig.iacId = 'instance.' + instance.id + '.' + resource.input.type + '.' + resource.input.key
		//set the zone
		appConfig.defaultCloud = opts.cloud
		//build layout config
		appConfig.layoutConfig = resource.layoutConfig ?: appConfig.layoutConfig
		//configure it
		rtn = configureResource(appConfig, resource, opts, morpheusContext)
		//done
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse configureResource(App app, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		def appConfig = [name:app.name, createdBy:app.createdBy, account:app.account,
						 serverGroup:app.serverGroup, resourcePool:app.resourcePool, site:app.site,
						 appId:app.id, appName:app.name, user:app.createdBy, refType:'app', refId:app.id, refObj:app,
						 refConfig:app.getConfigMap()
		]
		//build an iacId
		appConfig.iacId = 'app.' + app.id + '.' + resource.input.type + '.' + resource.input.key
		//set the zone
		appConfig.defaultCloud = opts.cloud
		//build layout config
		appConfig.layoutConfig = resource.layoutConfig ?: appConfig.layoutConfig
		//configure it
		rtn = configureResource(appConfig, resource, opts, morpheusContext)
		//if this is instance - add to app
		if(rtn.data.type == 'instance' && rtn.data.output && rtn.data.newResource) {
			def existingMatch = app.instances?.find{ it.id == rtn.data.output.id }
			if(existingMatch == null) {
				def appInstance = new AppInstance(app:app, instance:rtn.data.output)
				app.instances += appInstance
				morpheusContext.async.app.save(app).blockingGet()
			}
		}
		//done
		return ServiceResponse.create(rtn)
	}


	static ServiceResponse configureResource(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		//resource [input:[type, key, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType], mapping[:]]

		//get the type
		def morpheusType = resource.mapping.morpheusType
		//configure the resource from the mapping
		switch(morpheusType) {
			case 'instance':
				rtn = configureInstance(appConfig, resource, opts, morpheusContext)
				break
			case 'container':
				rtn = configureContainer(appConfig, resource, opts, morpheusContext)
				break
			case 'accountResource':
				rtn = configureAccountResource(appConfig, resource, morpheusContext)
				break
//			case 'zone':
//			case 'computeZone':
//				rtn = configureComputeZone(appConfig, resource, opts)
//				break
//			case 'service':
//			case 'serviceEntry':
//				rtn = configureServiceEntry(appConfig, resource, opts)
//				break
		}
		//add a morpheus resource?
		def morpheusResource = resource.morpheusResource
		if(morpheusResource) {
			//add the just the spec
			morpheusResource.spec = resource.spec
			//map this
			log.debug("configuring morpheus resource: {}", morpheusResource.mapping)
			def morpheusResults = configureResource(appConfig, morpheusResource, opts, morpheusContext)
			if(morpheusResults && morpheusResults.success == true)
				rtn.data.morpheusResource = morpheusResults
		}

		return ServiceResponse.create(rtn)
	}

	static ServiceResponse configureInstance(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		//resource [input:[type, key, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType], mapping[:]]
//		def morpheusType = resource.input.morpheusType
//		//iac id
//		def iacId = resource.iacId ?: (resource.mapping.iacId ?: appConfig.iacId)
//		//find a match so it isn't duplicated
//		def instanceMatch = Instance.findByIacId(iacId)
//		def containerMatchList = Container.findAllByIacId(iacId)
//		def serverMatchList = ComputeServer.findAllByIacId(iacId)
//		//add in any manually specified servers
//		if(opts.serverMatchList)
//			serverMatchList += opts.serverMatchList
//		//config
//		def objConfig = [account:appConfig.account, site:appConfig.site]
//		objConfig += resource.mapping
//		objConfig.iacId = iacId
//		//user data
//		def userData = objConfig.userData ?: objConfig.config?.userData
//		if(userData)
//			rtn.data.userData = userData
//		//default cloud
//		objConfig.zone = appConfig.zone
//		//plan and layouts
//		objConfig.plan = ServicePlan.findByCode('container-unmanaged') //figure out container vs vm?
//		def layoutConfig = resource.layoutConfig ?: appConfig.layoutConfig
//		objConfig.instanceType = layoutConfig.type
//		objConfig.layout = layoutConfig.layout
//		objConfig.workloadType = layoutConfig.workloadType
//		objConfig.provisionType = objConfig.layout?.provisionType
//		objConfig.platform = 'unknown'
//		objConfig.osType = OsType.findByCode('other.64')
//		objConfig.osType = getOsType(appConfig, resource)
//		if(layoutConfig.name) {
//			objConfig.name = layoutConfig.name
//		}
//		//get compute server type
//		objConfig.computeServerType = getCloudUnmanagedServerType(objConfig.zone)
//		//sizing & status
//
//		objConfig.maxMemory = objConfig?.maxMemory ?: objConfig.plan?.maxMemory
//		objConfig.maxCpu = objConfig?.maxCpu ?: objConfig.plan?.maxCpu ?: 1
//		objConfig.maxCores = objConfig?.maxCores ?: objConfig.plan?.maxCores ?: 1
//		objConfig.maxStorage = objConfig?.maxStorage ?: objConfig.plan?.maxStorage
//		objConfig.status = Instance.Status.provisioning
//		//get container list
//		def specContainers = objConfig.remove('containers')
//		//figure out if we will create
//		def createServer = objConfig.provisionType.createServer == true ? (objConfig.zone != null && objConfig.computeServerType != null) : false
//		def createInstance = (objConfig.layout != null && objConfig.containerType != null) && (createServer == true || objConfig.serverId != null)
//		//backup
//		def backupConfig = backupService.getDefaultBackupConfig(appConfig.account, appConfig.site, objConfig.zone,
//				[instanceType:objConfig.instanceType, layout:objConfig.layout, containerType:objConfig.containerType])
//		//count
//		def instanceCount = objConfig.count ?: 1
//		//instance
//		def newObj
//		def newServers = []
//		def newContainers = []
//		log.debug("instanceMatch: {} - createInstance: {}", instanceMatch, createInstance)
//		//if a match don't create
//		if(instanceMatch) {
//			newObj = instanceMatch
//			rtn.data.newResource = false
//		} else if(createInstance == true) {
//			//create it
//			def instanceName = getUniqueInstanceName(objConfig.name, objConfig.site.account)
//			def displayName = objConfig.name ?: instanceName
//			if(objConfig.createdBy instanceof String) {
//				objConfig.remove('createdBy')
//			}
//			newObj = new Instance(objConfig)
//			newObj.networkLevel = 'container'
//			if(objConfig.createdById) {
//				newObj.createdBy = User.get(objConfig.createdById.toLong())
//			} else {
//				newObj.createdBy = objConfig.createdBy ?: appConfig.createdBy
//			}
//			newObj.setConfigProperty('backup', backupConfig)
//			newObj.setConfigProperty('createBackup', backupConfig.createBackup)
//			newObj.save(failOnError:true)
//			rtn.data.newResource = true
//		}
//		//create containers?
//		//for each counter...
//		for(int i = 0; i < instanceCount; i++) {
//			//servers
//			def newServer = (serverMatchList.size() > i) ? serverMatchList.first() : null
//			if(newServer || createServer) {
//				if(newServer) {
//					serverMatchList.remove(newServer)
//				} else if(createServer) {
//					def serverConfig = [account:objConfig.site.account, computeServerType:objConfig.computeServerType, zone:objConfig.zone,
//										iacId:iacId, name:objConfig.name, displayName:objConfig.displayName ?: objConfig.name, sshUsername:'root',
//										hostname:objConfig.hostname, provisionSiteId:appConfig.site?.id, plan:objConfig.plan, serverOs:objConfig.osType,
//										osType:objConfig.osType?.platform, serverType:objConfig.serverType, singleTenant:(objConfig.singleTenant != null ? objConfig.singleTenant : true),
//										resourcePool:objConfig.resourcePool, externalId:objConfig.externalId, maxCpu:objConfig.maxCpu, maxCores:objConfig.maxCores,
//										maxMemory:objConfig.maxMemory, maxStorage:objConfig.maxStorage
//					]
//					newServer = new ComputeServer(serverConfig)
//					newServer.save(failOnError:true)
//				}
//				//update creds if creating
//				log.debug("instance user data check: {} - username: {}", userData, newServer.sshUsername)
//				if(newServer && userData?.found && newServer.sshUsername == 'root') {
//					newServer.sshUsername = userData.user ?: 'root'
//					newServer.sshPassword = userData.password ?: newServer.sshPassword
//					if(userData.privateKey)
//						newServer.privateKey = userData.privateKey
//					newServer.save()
//				}
//				//add to list
//				if(newServer)
//					newServers << newServer
//			} else if(objConfig.serverId) {
//				newServer = ComputeServer.get(objConfig.serverId)
//			}
//			//container
//			if(createInstance) {
//				//handle multiple container
//				if(objConfig.containerList) {
//					objConfig.containerList?.each { row ->
//						//create one - need to add a way to handle multiple though
//						def serverId = row.remove('serverId')
//						def containerConfig = row + [account:objConfig.site.account, containerType:objConfig.containerType,
//													 instance:newObj, plan:objConfig.plan, status:Container.Status.deploying, containerCreated:true,
//													 maxCpu:objConfig.maxCpu, maxCores:objConfig.maxCores, maxMemory:objConfig.maxMemory,
//													 maxStorage:objConfig.maxStorage
//						]
//						containerConfig.resourcePool = newObj.resourcePool
//						containerConfig.server = serverId ? ComputeServer.get(serverId) : newServer
//						def newContainer = new Container(containerConfig)
//						newContainer.save(failOnError:true)
//						newObj.addToContainers(newContainer)
//						newContainers << newContainer
//					}
//				} else {
//					def newContainer
//					if(containerMatchList.size() > i) {
//						newContainer = containerMatchList.find{ it.server == newServer }
//						if(!newContainer)
//							newContainer = containerMatchList.first()
//					}
//					if(newContainer) {
//						containerMatchList.remove(newContainer)
//					} else {
//						//create one - need to add a way to handle multiple though
//						def containerConfig = [account:objConfig.site.account, containerType:objConfig.containerType,
//											   hostname:objConfig.hostname, iacId:iacId, name:objConfig.name, instance:newObj, plan:objConfig.plan,
//											   server:newServer, status:Container.Status.deploying, containerCreated:true, maxCpu:objConfig.maxCpu,
//											   maxCores:objConfig.maxCores, maxMemory:objConfig.maxMemory, maxStorage:objConfig.maxStorage]
//						newContainer = new Container(containerConfig)
//						newContainer.save(failOnError:true)
//						newObj.addToContainers(newContainer)
//					}
//					newContainers << newContainer
//				}
//			}
//		}
//		// newObj.addConfigProperty('evars', containerService.getInstanceEnvironmentVariables(newObj))
//		// environmentVariableService.setInstanceUserEnvironmentVariables(newObj, [])
//		// newObj.save()
//		//prepare results
//		rtn.data.output = newObj
//		rtn.data.type = 'instance'
//		rtn.data.id = newObj.id
//		rtn.data.iacId = iacId
//		rtn.data.containers = newContainers
//		rtn.data.servers = newServers
//		rtn.data.create = rtn.data.id != null
//		rtn.success = true
//		//done with instance
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse configureContainer(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		//resource [input:[type, key, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType], mapping[:]]
		def morpheusType = resource.input.morpheusType
		//iac id
		def iacId = resource.iacId ?: (resource.mapping.iacId ?: appConfig.iacId)
		//find a match so it isn't duplicated
		def containerMatchList = morpheusContext.async.workload.list(new DataQuery().withFilter('iacId', iacId)).toList().blockingGet()
		def serverMatchList = morpheusContext.async.computeServer.list(new DataQuery().withFilter('iacId', iacId)).toList().blockingGet()

		//add in any manually specified servers
		if(opts.serverMatchList)
			serverMatchList += opts.serverMatchList
		//config
		def objConfig = [account:appConfig.account, site:appConfig.site]
		objConfig += resource.mapping
		objConfig.iacId = iacId
		//user data
		def userData = objConfig.userData ?: objConfig.config?.userData
		if(userData)
			rtn.data.userData = userData
		//default cloud
		objConfig.cloud = appConfig.defaultCloud
		//plan and layouts
		def layoutConfig = resource.layoutConfig ?: appConfig.layoutConfig
		objConfig.plan = layoutConfig.plan ?: new ServicePlan(code: 'container-unmanaged') //figure out container vs vm?
		objConfig.instanceType = layoutConfig.type
		objConfig.layout = layoutConfig.layout
		objConfig.workloadType = layoutConfig.workloadType
		objConfig.provisionType = objConfig.layout?.provisionType
		objConfig.platform = 'unknown'
		objConfig.osType = layoutConfig.osType ?: new OsType(code: 'other.64')
		//get compute server type
		objConfig.computeServerType = layoutConfig.computeServerType ?: new ComputeServerType(code: 'amazonUnmanaged')
		//sizing & status
		objConfig.maxMemory = objConfig?.maxMemory ?: objConfig.plan?.maxMemory
		objConfig.maxCpu = objConfig?.maxCpu ?: objConfig.plan?.maxCpu ?: 1
		objConfig.maxCores = objConfig?.maxCores ?: objConfig.plan?.maxCores ?: 1
		objConfig.maxStorage = objConfig?.maxStorage ?: objConfig.plan?.maxStorage
		objConfig.status = Instance.Status.provisioning
		//figure out if we will create a server
		def createServer = objConfig.provisionType?.createServer == true ? (objConfig.cloud != null && objConfig.computeServerType != null) : false
		//see if the server already exists
		log.debug("create server: {}", createServer)
		def newServer
		if(objConfig.serverId)
			newServer = morpheusContext.async.computeServer.get(objConfig.serverId).blockingGet()
		if(newServer == null)
			newServer = serverMatchList?.size() > 0 ? serverMatchList.first() : null
		//create it if it doesnt exist
		if(newServer == null && createServer == true) {
			def serverConfig = [account:objConfig.site.account, computeServerType:objConfig.computeServerType, cloud:objConfig.cloud,
								iacId:iacId, name:objConfig.name, displayName:objConfig.displayName ?: objConfig.name, sshUsername:'root',
								hostname:objConfig.hostname, provisionSiteId:appConfig.site?.id, plan:objConfig.plan, serverOs:objConfig.osType,
								osType:objConfig.osType?.platform, serverType:objConfig.serverType, singleTenant:(objConfig.singleTenant != null ? objConfig.singleTenant : true),
								resourcePool:objConfig.resourcePool, externalId:objConfig.externalId, maxCpu:objConfig.maxCpu, maxCores:objConfig.maxCores,
								maxMemory:objConfig.maxMemory, maxStorage:objConfig.maxStorage
			]
			newServer = new ComputeServer(serverConfig)
			if(resource.awsConfig) {
				newServer.setConfigProperty('awsConfig', resource.awsConfig)
				//get the matching image?
				def awsConfig = resource.awsConfig
				def amazonClient = opts.amazonClient
				if (awsConfig?.imageId) {
					//save the image
					def awsImage
					def awsResults = AmazonComputeUtility.loadImage([amazonClient: amazonClient, imageId: awsConfig.imageId])
					if (awsResults.success == true && awsResults.image)
						awsImage = awsResults.image

					//set os info
					if (awsImage) {
						newServer.osType = awsImage.getPlatform() == 'windows' ? 'windows' : 'linux'
						//server.isCloudInit = server.osType == 'windows' ? false : true
						newServer.serverOs = new OsType(code: newServer.osType)
					}
				}

			}
			if(appConfig.refConfig?.regionCode) {
				newServer.region = new CloudRegion(code: appConfig.refConfig.regionCode)
			}
			newServer = morpheusContext.async.computeServer.create(newServer).blockingGet()
		}

		//update creds if creating
		log.debug("container user data check: {} - username: {}", userData, newServer?.sshUsername)
		if(newServer && userData?.found && newServer.sshUsername == 'root') {
			if(resource.awsConfig) {
				newServer.setConfigProperty('awsConfig', resource.awsConfig)
			}
			newServer.sshUsername = userData.user ?: 'root'
			newServer.sshPassword = userData.password ?: newServer.sshPassword
			if(userData.privateKey)
				newServer.privateKey = userData.privateKey
			morpheusContext.async.computeServer.save(newServer).blockingGet()
			newServer = morpheusContext.async.computeServer.get(newServer.id).blockingGet()
		}

		//find or create the container
		def newObj
		if(containerMatchList.size() > 0) {
			newObj = containerMatchList.find{ it.server == newServer }
			if(!newObj)
				newObj = containerMatchList.first()
		}
		//create it if not
		if(newObj == null) {
			//create one - need to add a way to handle multiple though
			def containerConfig = [account:objConfig.site.account, workloadType:objConfig.workloadType,
								   hostname:objConfig.hostname, iacId:iacId, displayName:objConfig.name,
								   plan:objConfig.plan, server:newServer, status: Workload.Status.deploying,
								   containerCreated:true, maxCpu:objConfig.maxCpu, maxCores:objConfig.maxCores,
								   maxMemory:objConfig.maxMemory, maxStorage:objConfig.maxStorage]
			if(appConfig.refType == 'instance')
				containerConfig.instance = appConfig.refObj
			//create it
			log.debug("create container config: {}", containerConfig)
			newObj = new Workload(containerConfig)
			//newObj = morpheusContext.async.workload.create(newObj).blockingGet()
			rtn.data.newResource = true
			rtn.data.container = newObj
		}
		//prepare results
		rtn.data.output = newObj
		rtn.data.type = 'container'
		rtn.data.id = newObj.id
		rtn.data.iacId = iacId
		rtn.data.create = rtn.data.id != null
		rtn.success = true
		log.info("create container results: {}", rtn)
		//done with instance
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse createAppTemplateResource(App app, Cloud cloud, Map config, Map masterConfig, MorpheusContext morpheusContext, Map opts) {
		def rtn
		def rtnData = [config:config, newResource:true, userData:[found:false]]
		//standard shared way to create template resources
		def configResponse = configureAppTemplateResource(app, cloud, config, masterConfig, morpheusContext, opts)
		def extraConfig = configResponse.data
		log.debug("extraConfig: {}", extraConfig)
		//create by type
		if(extraConfig.create == true && (config.morpheusType == 'zone' || config.morpheusType == 'computeZone')) { //cloud support
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def zoneConfig = [account:app.account, owner:app.account, visibility:'private', site:app.site]
			zoneConfig += extraConfig.zone
			def zoneOptions = extraConfig.config ?: [:]
			def newCloud = new Cloud(zoneConfig)
			newCloud.setConfigMap(zoneOptions)
			//setZoneIntegrations(rtn.zone, config)
			//def subService = getService(rtn.zone.zoneType.computeService)
			newCloud = morpheusContext.async.cloud.create(newCloud).blockingGet()
			rtnData.zone = newCloud
			rtnData.resource = newCloud
			rtnData.id = newCloud.id
			rtnData.iacId = iacId
			rtnData.create = true
			config.provider.computeZone = newCloud
			rtn = ServiceResponse.success(rtnData)
		}	else if(config.morpheusType == 'instanceTypeLayout') { //new layout
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			//make a layout and container set and container type
			rtnData.create = false
			rtnData.iacId = iacId
			rtn = ServiceResponse.success(rtnData)
		} else if(extraConfig.create == true && config.morpheusType == 'computeZonePool') {
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def targetZone = cloud ?: extraConfig.zone
			def poolConfig = [owner:app.account, name:config.name, refType:'ComputeZone', refId:targetZone.id, zone:targetZone,
							  iacId:iacId, status: CloudPool.Status.deploying]
			poolConfig += extraConfig.computeZonePool ?: [:]
			def poolOptions = extraConfig.config ?: [:]
			def newPool = new CloudPool(poolConfig)
			newPool.setConfigMap(poolOptions)
			newPool = morpheusContext.async.cloud.pool.create(newPool).blockingGet()
//			def resourcePerm = new ResourcePermission(morpheusResourceType:'ComputeZonePool', morpheusResourceId:newPool.id, account:targetZone.account)
//			resourcePerm.save(flush:true)
			rtnData.computeZonePool = newPool
			rtnData.resource = newPool
			rtnData.id = newPool.id
			rtnData.iacId = iacId
			rtnData.create = false
			rtn = ServiceResponse.success(rtnData)

		} else if(extraConfig.create == true && config.morpheusType == 'instanceScale') {
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def targetZone = cloud ?: extraConfig.zone
			def instanceScaleConfig = [owner:app.account, name:config.name, zoneId:targetZone.id, zone:targetZone, iacId: iacId, status: InstanceScale.Status.deploying]
			instanceScaleConfig += extraConfig.instanceScale ?: [:]
			def instanceScaleOptions = extraConfig.config ?: [:]
			def newInstanceScale = new InstanceScale(instanceScaleConfig)
			newInstanceScale.setConfigMap(instanceScaleOptions)
			morpheusContext.async.instance.scale.create(newInstanceScale).blockingGet()

			rtnData.instanceScale = newInstanceScale
			rtnData.resource = newInstanceScale
			rtnData.id = newInstanceScale.id
			rtnData.iacId = iacId
			rtnData.create = false
			rtn = ServiceResponse.success(rtnData)

		} else if(extraConfig.create == true && config.morpheusType == 'securityGroupLocation') {
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def targetZone = cloud ?: extraConfig.zone

			def securityGroupLocationConfig = [refType:'ComputeZone', refId:targetZone.id, name:config.name,
											   iacId  : iacId, status: SecurityGroupLocation.Status.deploying, description:config.description, groupName:config.name]
			securityGroupLocationConfig += extraConfig.securityGroupLocation ?: [:]

			// Create a SecurityGroup
			SecurityGroup securityGroup = new SecurityGroup(account:app.account, name:securityGroupLocationConfig.name, description:securityGroupLocationConfig.description)
			securityGroup = morpheusContext.async.securityGroup.create(securityGroup).blockingGet()

			// Create a SecurityGroupLocation
			def securityGroupLocationOptions = extraConfig.config ?: [:]
			def newSecurityGroupLocation = new SecurityGroupLocation(securityGroupLocationConfig)
			newSecurityGroupLocation.setConfigMap(securityGroupLocationOptions)
			newSecurityGroupLocation.securityGroup = securityGroup
			newSecurityGroupLocation = morpheusContext.securityGroup.location.create(newSecurityGroupLocation).blockingGet()
			securityGroup.locations += newSecurityGroupLocation
			morpheusContext.securityGroup.save([securityGroup]).blockingGet()

			rtnData.securityGroupLocation = newSecurityGroupLocation
			rtnData.resource = newSecurityGroupLocation
			rtnData.id = newSecurityGroupLocation.id
			rtnData.iacId = iacId
			rtnData.create = false
			rtn = ServiceResponse.success(rtnData)

		} else if(extraConfig.create == true && config.morpheusType == 'network') {
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def targetZone = cloud ?: extraConfig.zone
			def networkConfig = [owner: targetZone.owner, name:config.name, status: 'deploying', refType:'ComputeZone', iacId:iacId,
								 refId:targetZone.id, active:true, dhcpServer:true, networkServer:targetZone.networkServer, zone:targetZone]
			networkConfig += extraConfig.networkConfig ?: [:]
			def newNetwork = new Network(networkConfig)
			rtnData.network = newNetwork
			rtnData.resource = newNetwork
			rtnData.id = newNetwork.id
			rtnData.iacId = iacId
			rtnData.create = false
			rtn = ServiceResponse.success(rtnData)
		} else

		if(config.morpheusType == 'instance') { //instance support
			//config
			def iacId = extraConfig.iacId ?: 'app.' + app.id + '.' + config.type + '.' + config.key
			def instanceCount = config.count ?: 1
			//find a match so it isn't duplicated
			def instanceMatch = morpheusContext.async.instance.find(new DataQuery().withFilter('iacId', iacId)).blockingGet()
			def workloadMatchList = morpheusContext.async.workload.list(new DataQuery().withFilter('iacId', iacId)).toList().blockingGet()
			def serverMatchList = morpheusContext.async.computeServer.list(new DataQuery().withFilter('iacId', iacId)).toList().blockingGet()
			//prep config
			def instanceType = extraConfig.instanceType
			def site = app.site
			def layout = extraConfig.layout
			def plan = extraConfig.plan
			def provisionType = layout?.provisionType
			def osType = extraConfig.osType
			def computeServerType = extraConfig.computeServerType
			def containerType = extraConfig.containerType
			def targetCloud = cloud ?: extraConfig.zone
			//credentials
			if(config.userData)
				rtnData.userData = config.userData
			//size info?
			def maxMemory = extraConfig?.maxMemory ?: plan?.maxMemory
			def maxCpu = extraConfig?.maxCpu ?: plan?.maxCpu
			def maxCores = extraConfig?.maxCores ?: plan?.maxCores
			def maxStorage = extraConfig?.maxStorage ?: plan?.maxStorage
			def createServer = provisionType.createServer == true ? (targetCloud != null && computeServerType != null) : false
			def createInstance = (layout != null && containerType != null) && (createServer == true || config.serverId != null)
			//backup
//			def backupConfig = backupService.getDefaultBackupConfig(app.account, app.site, targetZone,
//					[instanceType:instanceType, layout:layout, containerType:containerType])
			//instance
			def newInstance
			def newServers = []
			def newContainers = []
			log.debug("instanceMatch: {} - createInstance: {}", instanceMatch, createInstance)
			//if a match don't create
			if(instanceMatch) {
				newInstance = instanceMatch
				rtnData.newResource = false
			} else if(createInstance == true) {
				def instanceName = getUniqueInstanceName(config.name, site.account, morpheusContext)
				def displayName = extraConfig.name ?: instanceName
				def instanceConfig = [account:site.account, name:instanceName, displayName:displayName, iacId:iacId,
									  instanceTypeCode:instanceType.code, layout:layout, plan:plan,
									  createdBy:(config.createdBy ?: app.createdBy), site:app.site, networkLevel:'container',
									  status:Instance.Status.provisioning, maxCores:maxCores ?: 1,
									  maxMemory:maxMemory, maxStorage:maxStorage
				]
				newInstance = new Instance(instanceConfig)
				//newInstance.setConfigProperty('backup', backupConfig)
				//newInstance.setConfigProperty('createBackup', backupConfig.createBackup)
				newInstance = morpheusContext.async.instance.create(newInstance).blockingGet()
			}
			//for each counter...
			for(int i = 0; i < instanceCount; i++) {
				//servers
				def newServer = (serverMatchList.size() > i) ? serverMatchList.first() : null
				if(newServer || createServer) {
					if(newServer) {
						serverMatchList.remove(newServer)
					} else if(createServer) {
						def serverConfig = [account:site.account, computeServerType:computeServerType, cloud:targetCloud, iacId:iacId, name:config.name,
											sshUsername:'root', hostname:config.hostname, provisionSiteId:app.site?.id, plan:plan, serverOs:osType,
											osType:osType?.platform, serverType:config.serverType, singleTenant:(config.singleTenant != null ? config.singleTenant : true),
											resourcePool: config.resourcePool, externalId: config.externalId,
											maxCpu:maxCpu ?: 1, maxCores:maxCores ?: 1, maxMemory:maxMemory, maxStorage:maxStorage
						]
						newServer = new ComputeServer(serverConfig)
						newServer = morpheusContext.async.computeServer.create(newServer).blockingGet()
					}
					//update creds if creating
					if(newServer && rtnData.userData.found && newServer.sshUsername == 'root') {
						newServer.sshUsername = rtnData.userData.user ?: 'root'
						newServer.sshPassword = rtnData.userData.password ?: newServer.sshPassword
						if(rtnData.userData.privateKey)
							newServer.privateKey = rtnData.userData.privateKey
						morpheusContext.async.computeServer.save(newServer).blockingGet()
					}
					//add to list
					if(newServer)
						newServers << newServer
				} else if(config.serverId) {
					newServer = morpheusContext.async.computeServer.get(config.serverId as Long).blockingGet()
				}
				//container
				if(createInstance) {
					//handle multiple container
					if(config.containerList) {
						config.containerList?.each { row ->
							//create one - need to add a way to handle multiple though
							def workloadConfig = [account:site.account, workloadType: containerType, hostname:row.hostname,
												   iacId:iacId + '.' + row.iacId, name:row.name, instance:newInstance, plan:plan,
												   status:Container.Status.deploying, containerCreated:true, maxCpu:maxCpu ?: 1, maxCores:maxCores ?: 1,
												   maxMemory:maxMemory, maxStorage:maxStorage, displayName:row.displayName, category:row.category,
												   repositoryImage:row.repositoryImage, containerVersion:row.containerVersion
							]
							workloadConfig.server = row.serverId ? new ComputeServer(id: row.serverId) : newServer
							def newWorkload = new Workload(workloadConfig)
							newWorkload = morpheusContext.async.workload.create(newWorkload).blockingGet()
							newInstance.containers += newWorkload
							newContainers << newWorkload
						}
					} else {
						def newWorkload
						if(workloadMatchList.size() > i) {
							newWorkload = workloadMatchList.find{ it.server == newServer }
							if(!newWorkload)
								newWorkload = workloadMatchList.first()
						}
						if(newWorkload) {
							workloadMatchList.remove(newWorkload)
						} else {
							//create one - need to add a way to handle multiple though
							def workloadConfig = [account:site.account, workloadType: containerType, hostname:config.hostname, iacId:iacId,
												   internalName:config.name, instance:newInstance, plan:plan, server:newServer, status:Workload.Status.deploying,
												   containerCreated:true, maxCpu:maxCpu ?: 1, maxCores:maxCores ?: 1, maxMemory:maxMemory, maxStorage:maxStorage]
							newWorkload = new Workload(workloadConfig)
							newWorkload = morpheusContext.async.workload.create(newWorkload).blockingGet()
							newInstance.containers += newWorkload
						}
						newContainers << newWorkload
					}
				}
			}
			//save it
			if(newInstance)
				morpheusContext.async.instance.save(newInstance).blockingGet()
			//if its new - add it to the app
			if(newInstance && rtnData.newResource) {
				def existingMatch = app.instances?.find{ it.id == newInstance.id }
				if(existingMatch == null) {
					def appInstance = new AppInstance(app:app, instance:newInstance)
					app.instances += appInstance
					morpheusContext.async.app.save(app).blockingGet()
				}
			}
			//build return data
			rtnData.instance = newInstance
			rtnData.resource = newInstance
			rtnData.containers = newContainers
			rtnData.servers = newServers
			rtnData.id = newInstance?.id
			rtnData.iacId = iacId
			rtnData.create = rtnData.id != null
			rtn = ServiceResponse.success(rtnData)
		} else {
			rtn = ServiceResponse.error('type not supported')
		}

		return rtn
	}

	static ServiceResponse configureAppTemplateResource(App app, Cloud cloud, Map config, Map masterConfig, MorpheusContext morpheusContext, Map opts) {
		def extraConfig = [create:false]
		if(config.morpheusType == 'zone') {
			extraConfig.zone = [:] //map to add to zone
			extraConfig.config = [:] //map for zone confg
			//name it
			def name
			if(config.tags && config.tags['Name'])
				name = config.tags['Name']
			else
				name = config.name
			//proceed if we have a name
			if(name) {
				extraConfig.zone.name = name
				extraConfig.zone.zoneType = new CloudType(code: 'amazon')
				//keep going
				def awsProvider = config.provider ?: masterConfig.terraformConfig.config.provider['aws']
				log.debug("provider: {}", awsProvider)
				if(awsProvider) {
					def region = awsProvider.region
					//lookup the match
					def regionMatch = morpheusContext.async.referenceData.find(new DataQuery().withFilters([
					 		new DataFilter('category', 'amazon.ec2.region'),
							new DataFilter('keyValue', region)
					])).blockingGet()
					if(regionMatch) {
						//set the endpoint
						extraConfig.config.endpoint = regionMatch.value
						//set the creds
						extraConfig.config.accessKey = awsProvider['access_key']
						extraConfig.config.secretKey = awsProvider['secret_key']
						extraConfig.zone.inventoryLevel = 'full'
						extraConfig.config.vpc = config.name
						//say to create
						extraConfig.create = true
					}
				}
			}
		} else
		if(config.morpheusType == 'instance') {
			def baseConfig = app.getConfigMap()
			def appConfig	= baseConfig.config ? new groovy.json.JsonSlurper().parseText(baseConfig.config) : [:]
			log.debug("baseConfig: {} appConfig: {}", baseConfig, appConfig)
			//default cloud
			def targetZone = opts.cloud
			if(targetZone) {
				def tfType = config.type
				if(tfType == 'aws_autoscaling_group') {
					def templateMatch
					//find the template or config - and use it to get info


				} else {
					extraConfig.zone = targetZone
					//app config
					extraConfig.iacId = 'app.' + app.id + '.' + config.type + '.' + config.key
					def imageId = config.ami
					//set extra config
					extraConfig.plan = config.instance_type ? new ServicePlan(externalId: config.instance_type) : null
					extraConfig.instanceType = new InstanceType(code: 'amazon')
					extraConfig.layout = morpheusContext.async.instanceTypeLayout.find(new DataQuery().withFilter('code', 'amazon-1.0-single')).blockingGet()
					extraConfig.containerType = new WorkloadType(code: 'amazon-1.0')
					def nameTag = config?.tags ? config.tags['Name'] : null
					if(nameTag)
						extraConfig.name = nameTag
					def platform = 'linux'
					if(imageId) {
						def imageResults = EC2ProvisionProvider.saveAccountImage(opts.amazonClient, app.account, targetZone, imageId)
						if (imageResults.image) {
							extraConfig.osType = imageResults.image.osType ?: new OsType(code: 'other.64')
							platform = extraConfig.osType?.platform ?: 'linux'
						}
					}
					//cs type
					def csTypeCode = platform == 'windows' ? 'amazonWindowsVm' : 'amazonVm'
					extraConfig.computeServerType = new ComputeServerType(code: csTypeCode)
					extraConfig.create = true
				}
			}
		} else if(config.morpheusType == 'computeZonePool') {
			def targetZone = opts.cloud
			def vpcId = targetZone.getConfigProperty('vpc')
			extraConfig.create = !vpcId
			extraConfig.zone = targetZone
			extraConfig.computeZonePool = [ type: 'vpc', category:"aws.vpc.${targetZone.id}"]
		} else if(config.morpheusType == 'network') {
			def targetZone = opts.cloud
			extraConfig.create = true
			extraConfig.networkConfig = [category:"amazon.ec2.subnet.${targetZone.id}", type: new NetworkType(code: 'amazonSubnet'), externalType: 'subnet']
		} else if(config.morpheusType == 'instanceScale') {
			extraConfig.create = true
			extraConfig.instanceScale = [ type: new InstanceScaleType(code: 'plugin.awsscalegroup') ]
		} else if(config.morpheusType == 'securityGroupLocation') {
			extraConfig.create = true
		}
		return ServiceResponse.success(extraConfig)
	}


	static ServiceResponse configureAccountResource(Map appConfig, Map resource, MorpheusContext morpheusContext) {
		def rtn = [success:false, data:[newResource:false]]
		log.debug "configureAccountResource: appConfig: ${appConfig} resource: ${resource}"
		//resource [input:[type, key, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType], mapping[:]]
		def morpheusType = resource.input.morpheusType
		//iac id
		def iacId = resource.iacId ?: (resource.mapping.iacId ?: appConfig.iacId)
		//generic mapping
		//println("resource create mapping: ${resource.mapping}")
		def mapping = resource.mapping
		def mappingConfig = mapping.containsKey('config') ? mapping.remove('config') : null

		mapping.remove('morpheusType')
		def resourceSpec = mapping.containsKey('spec') ? mapping.remove('spec') : null
		mapping.resourceSpec = resourceSpec

		def newObj = new AccountResource(mapping)
		if(mappingConfig)
			newObj.setConfigMap(mappingConfig)
		morpheusContext.async.cloud.resource.create(newObj).blockingGet()
		//prepare results
		rtn.data.output = newObj
		rtn.data.type = 'accountResource'
		rtn.data.id = newObj.id
		rtn.data.iacId = iacId
		rtn.data.create = true
		rtn.data.newResource = true
		rtn.success = true
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse buildAccountResource(Map appConfig, Map resource, Map opts) {
		def rtn = [success:false, data:null]
		if(resource?.apiType) {
			//make an account resource
			def resourceConfig = [owner:appConfig.account, resourceSpec:resource.resourceSpec,
								  name:resource.name, displayName:resource.name, resourceType:resource.type,
								  iacType:resource.iacType, iacProvider:resource.iacProvider, iacId:resource.iacId,
								  iacKey:resource.iacKey, iacName:resource.iacName, iacIndex:resource.iacIndex,
								  morpheusType:'accountResource'
			]
			//set the version if found
			if(appConfig.appVersion)
				resourceConfig.appVersion = appConfig.appVersion
			//type
			if(resource.resourceType) {
				resourceConfig.type = resource.resourceType
				resourceConfig.resourceIcon = resource.resourceType.resourceIcon
			}
			//key
			if(resource.key) {
				resourceConfig.resourceKey = resource.key
			}
			//external id
			if(resource.externalId) {
				resourceConfig.externalId = resource.externalId
			}
			//resource pool
			if(appConfig.resourcePool) {
				resourceConfig.resourcePoolId = appConfig.resourcePool.id
				resourceConfig.resourcePoolName = appConfig.resourcePool.name
			}
			//server group
			if(appConfig.serverGroup) {
				resourceConfig.serverGroupId = appConfig.serverGroup.id
				resourceConfig.serverGroupName = appConfig.serverGroup.name
			}
			//add zone
			if(resource.zone) {
				resourceConfig.zoneId = resource.zone.id
				resourceConfig.zoneName = resource.zone.name
			}
			//add site
			if(appConfig.site) {
				resourceConfig.siteId = appConfig.site.id
				resourceConfig.siteName = appConfig.site.name
			}
			//plan
			if(appConfig.plan) {
				resourceConfig.planId = appConfig.plan.id
				resourceConfig.planName = appConfig.plan.name
			}
			//layout
			if(appConfig.layout) {
				resourceConfig.layoutId = appConfig.layout.id
				resourceConfig.layoutName = appConfig.layout.name
			}
			//instance
			if(appConfig.instanceId) {
				resourceConfig.instanceId = appConfig.instanceId
				resourceConfig.instanceName = appConfig.instanceName
			}
			//user
			if(appConfig.user) {
				resourceConfig.userId = appConfig.user.id
				resourceConfig.userName = appConfig.user.username
				resourceConfig.createdById = resourceConfig.userId
				resourceConfig.updatedById = resourceConfig.userId
				resourceConfig.createdBy = resourceConfig.userName
				resourceConfig.updatedBy = resourceConfig.userName
			}
			//status
			resourceConfig.status = 'deploying'
			//containerId, containerName, serverId, serverName, tags, code, category, resourceContext,
			//resourceVersion, resourceSpec
			resourceConfig.specData = resource.specData
			resourceConfig.rawData = resource.spec
			//done
			rtn.data = resourceConfig
			rtn.success = true
		}
		return ServiceResponse.create(rtn)
	}

	//individual mappings - takes the map of input and mapped data returned by the map methods
	//  returns config for a morpheus object
	static ServiceResponse mapInstance(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		log.debug("mapInstance: {}, {}", appConfig, resource)
		def rtn = [success:false, data:[resource:null]]
		////format: [type, apiType, apiPath, namespace, enabled, spec, specMap, morpheusType, accountResource]
		try {
			//get the parsed resource mapping
			def resourceConfig = resource.accountResource ?: resource.specMap
			//domain properties
			def mapResult = [account:appConfig.account, name:resource.name, displayName:resource.name,
							 createdBy:appConfig.createdBy, iacKey:resource.iacKey, iacId:resource.iacId, iacName:resource.iacName,
							 iacIndex:resource.iacIndex, morpheusType:'instance'
			]
			mapResult.config = resourceConfig?.config ?: [:]
			mapResult.config.spec = (resource.resourceSpec ?: resource.spec ?: [:])
			rtn.data.resource = [input:resource, mapping:mapResult]
		} catch(e) {
			log.error("error mapping instance: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	static ServiceResponse mapContainer(Map appConfig, Map resource, Map opts, MorpheusContext morpheusContext) {
		log.debug("map container: {}", resource)
		def rtn = [success:false, data:[resource:null]]
		def resourceConfig = resource.accountResource ?: resource.specMap
		//domain properties
		def mapResult = [account:appConfig.account, name:resource.name, displayName:resource.name,
						 iacKey:resource.iacKey, iacId:resource.iacId, iacName:resource.iacName,
						 iacIndex:resource.iacIndex, morpheusType:'container'
		]
		mapResult.config = resourceConfig?.config ?: [:]
		mapResult.config.spec = (resource.resourceSpec ?: resource.spec ?: [:])
		rtn.data.resource = [input:resource, mapping:mapResult]
		//handle custom stuff for cf
		def layoutConfig = [:]
		def templateInput = getTemplateParameters(appConfig)
		log.debug("templateInput {}", templateInput)
		def awsInstanceType = findAwsValue(resource.specMap, resource.resourceSpec, templateInput, 'InstanceType')
		def awsImageId = findAwsValue(resource.specMap, resource.resourceSpec, templateInput, 'ImageId')
		def awsKeyName = findAwsTypeValue(resource.specMap, templateInput, 'AWS::EC2::KeyPair::KeyName')
		rtn.data.resource.awsConfig = [instanceType:awsInstanceType, imageId:awsImageId, keyName:awsKeyName]
		//if the type is an ECS instance

		layoutConfig.plan = awsInstanceType ? morpheusContext.async.servicePlan.listByCode(['amazon-' + awsInstanceType]).blockingFirst() : null
		layoutConfig.instanceType = new InstanceType(code:'amazon')
		layoutConfig.layout = morpheusContext.async.instanceTypeLayout.find(new DataQuery().withFilter('code', 'amazon-1.0-single')).blockingGet()
		layoutConfig.computeServerType = new ComputeServerType(code:'amazonVm')
		layoutConfig.provisionType = layoutConfig.layout?.provisionType
		if(layoutConfig.layout?.workloads?.size() > 0) {
			def workloadTypeSetRef = layoutConfig.layout.workloads.first()
			if(workloadTypeSetRef) {
				def workloadTypeSet = morpheusContext.async.workload.typeSet.get(workloadTypeSetRef.id).blockingGet()
				layoutConfig.workloadType = workloadTypeSet.workloadType
			}
		}
		rtn.data.resource.layoutConfig = layoutConfig
		//done
		return ServiceResponse.create(rtn)
	}

	//helpers
	static def getTemplateParameters(Map appConfig) {
		def rtn = appConfig.refConfig?.templateParameter ?: [:]
		rtn['AWS::StackName'] = appConfig.name
		//add the region
		if(appConfig.region) {
			rtn['AWS::Region'] = appConfig.region
		}
		return rtn
	}

	static def findAwsValue(Map resourceSpec, Map itemSpec, Map templateInput, String key) {
		def rtn
		if(itemSpec) {
			def awsValue = itemSpec[key]
			if(awsValue == null) { //check properties
				def itemProps = itemSpec['Properties']
				awsValue = itemProps[key]
			}
			if(awsValue == null) { //check properties
				def itemMetadata = itemSpec['Metadata']
				awsValue = itemMetadata[key]
			}
			//get the value
			if(awsValue) {
				if(awsValue instanceof CharSequence) {
					//string - just use it
					rtn = awsValue
				} else if(awsValue instanceof Map) {
					//see if its a ref
					rtn = processFunction(resourceSpec, awsValue, templateInput)
				}
			}
		}
		log.debug("findAwsValue: {}", rtn)
		return rtn
	}

	static def findAwsTypeValue(Map resourceSpec, Map templateInput, String type) {
		def rtn
		def keyParameter = resourceSpec['Parameters']?.each { key, value ->
		if(value['Type'] == type) {
			if(value['Default'])
				rtn = value['Default']
				//check params
				if(templateInput) {
					if(templateInput[key])
						rtn = templateInput[key]
				}
			}
		}
		return rtn
	}

	static processFunction(Map resourceSpec, Map functionMap, Map templateInput) {
		//see if its a ref
		def rtn
		def typeRef = functionMap['Ref']
		if(typeRef) {
			rtn = processFindRef(resourceSpec, typeRef, templateInput)
		} else {
			def functionRef = functionMap['Fn::FindInMap']
			if(functionRef) {
				rtn = processFindInMap(resourceSpec, functionRef, templateInput)
			} else {
				//other types?
			}
		}
		return rtn
	}

	static processFindRef(Map resourceSpec, Object refItem, Map templateInput) {
		def rtn
		if(refItem instanceof CharSequence) {
			rtn = templateInput[refItem]
		} else if(refItem instanceof Map) {
			def refKey = processFunction(resourceSpec, refItem, templateInput)
			rtn = refKey ? templateInput[refKey] : null
		}
		return rtn
	}

	def processFindInMap(Map resourceSpec, Collection refConfig, Map templateInput) {
		//example : { "Fn::FindInMap" : [ "AWSRegionArch2AMI", 
		//	{ "Ref" : "AWS::Region" }, 
		//	{ "Fn::FindInMap" : [ 
		//    "AWSInstanceType2Arch", 
		//    { "Ref" : "InstanceType" }, 
		//    "Arch"  ]}]}
		def rtn
		if(refConfig && refConfig.size() == 3) {
			//value is [map name - key to look for - value to return]
			def mappingSet = resourceSpec['Mappings']
			def sourceMap
			def targetValue
			//first get the source mapping
			def mapKey = refConfig[0]
			if(mapKey instanceof CharSequence) {
				sourceMap = mappingSet[mapKey]
			} else if(mapKey instanceof Map) {
				//do the lookup then find it... - not sure if this is ever used
				def subKey = processFunction(resourceSpec, mapKey, templateInput)
				sourceMap = subKey ? mappingSet[subKey] : [:]
			}
			//have the map - now get the value
			def valueKey = refConfig[1]
			if(valueKey instanceof CharSequence) {
				targetValue = sourceMap[valueKey]
			} else if(valueKey instanceof Map) {
				//do the lookup then find it...
				def subKey = processFunction(resourceSpec, valueKey, templateInput)
				targetValue = subKey ? sourceMap[subKey] : [:]
			}
			//have the value - now get the output
			def outputKey = refConfig[2]
			if(outputKey instanceof CharSequence) {
				rtn = targetValue[outputKey]
			} else if(outputKey instanceof Map) {
				def subKey = processFunction(resourceSpec, outputKey, templateInput)
				rtn = subKey ? targetValue[subKey] : null
			}
		}
		return rtn
	}

	static mapResources(App app, Cloud cloud, Map config, Map opts, MorpheusContext morpheusContext) {
		def rtn = []

		config?.Resources?.each { logicalId, v ->
			//get type mapping
			def cloudFormationResourceMapping = [
					[type:'aws::ec2::instance', morpheusType:'instance', mapFunction: 'mapVirtualMachine', needsProvision: true, defaultLayout: opts.defaultLayout],
					[type:'aws::ec2::vpc', morpheusType:'computeZonePool', mapFunction: 'mapVPC', needsProvision: true],
					[type:'aws::ec2::subnet', morpheusType:'network', mapFunction: 'mapSubnet', needsProvision: true],
					[type:'aws::autoscaling::autoscalinggroup', morpheusType:'instanceScale', mapFunction: 'mapScaleGroup', needsProvision: true],
					[type:'aws::ec2::securitygroup', morpheusType:'securityGroupLocation', mapFunction: 'mapSecurityGroupLocation', needsProvision: true]
			]
			def resourceMap = cloudFormationResourceMapping.find{ it.type == v?.Type?.toString()?.toLowerCase() }
			if(resourceMap) {
				def mappedResource = "${resourceMap.mapFunction}"(app, resourceMap, cloud, logicalId, v)
				mappedResource.type = resourceMap.type
				mappedResource.key = mappedResource.name
				rtn << mappedResource
			} else {
				def type = v['Type']
				def typeMatch = findAwsResourceType(type as String, morpheusContext)
				if(typeMatch && typeMatch instanceof AccountResourceType) {
					def row = [type:typeMatch.type, apiType:typeMatch.apiType, enabled:typeMatch.enabled,
							   morpheusType:typeMatch.morpheusType, name: logicalId,
							   input: [type: typeMatch.type, key: logicalId], mapping: [morpheusType: 'accountResource',
																						type:typeMatch, apiType:typeMatch.apiType, enabled:typeMatch.enabled, name: logicalId,
																						iacProvider:'cloudFormation', iacType:typeMatch.apiType, resourceSpec:v, owner: app.account,
																						resourceType: type, zone: cloud],
							   iacProvider:'cloudFormation', iacType:typeMatch.apiType, resourceSpec:v,
							   resourceType: typeMatch
					]
					rtn << row
				}
			}
		}

		log.debug "mapResources: $rtn"
		return rtn
	}

	private static mapVirtualMachine(App app, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		def appConfig = [name:app.name, createdBy:app.createdBy]
		return mapVirtualMachine(appConfig, resourceMap, cloud, logicalId, config)
	}

	private static mapVirtualMachine(Map appConfig, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		log.debug("mapVirtualMachine: {}, {}", appConfig, resourceMap)
		def rtn = [
				name             : logicalId,
				key_name         : config?.Properties?.KeyName,
				key_id           : null, // populated during runApp
				image_id         : config?.Properties?.ImageId,
				hostname         : null,
				iacId            : null,
				serverType       : 'vm',
				singleTenant     : true,
				createdBy        : appConfig.createdBy,
				instanceType     : new InstanceType(code: 'amazon'),
				layout           : resourceMap.defaultLayout,
				instance_type    : 't2.small',
				osType           : new OsType(code: 'linux'),
				computeServerType: new ComputeServerType(code: 'amazonVm'),
				containerType    : new ContainerType(code: 'amazon-1.0' ),
				memory           : 1,
				cpuCount         : 1,
				coreCount        : 1,
				disk             : []
		] + resourceMap
		rtn.hostname = formatProvisionHostname(rtn.name)
		return rtn
	}

	private static mapVPC(App app, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		def appConfig = [name:app.name, createdBy:app.createdBy]
		return mapVPC(appConfig, resourceMap, cloud, logicalId, config)
	}

	private static mapVPC(Map appConfig, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		log.debug("mapVPC: {}, {}", appConfig, resourceMap)
		def rtn = [
				name : logicalId
		] + resourceMap
		return rtn
	}

	private static mapSubnet(App app, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		def appConfig = [name:app.name, createdBy:app.createdBy]
		return mapSubnet(appConfig, resourceMap, cloud, logicalId, config)
	}

	private static mapSubnet(Map appConfig, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		log.debug("mapSubnet: {}, {}", appConfig, resourceMap)
		def rtn = [
				name: logicalId
		] + resourceMap
		return rtn
	}

	private static mapScaleGroup(App app, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		def appConfig = [name:app.name, createdBy:app.createdBy]
		return mapScaleGroup(appConfig, resourceMap, cloud, logicalId, config)
	}

	private static mapScaleGroup(Map appConfig, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		log.debug("mapScaleGroup: {}, {}", appConfig, resourceMap)
		def rtn = [
				name: logicalId,
		] + resourceMap
		return rtn
	}

	private static mapSecurityGroupLocation(App app, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		def appConfig = [name:app.name, createdBy:app.createdBy]
		return mapSecurityGroupLocation(appConfig, resourceMap, cloud, logicalId, config)
	}

	private static mapSecurityGroupLocation(Map appConfig, Map resourceMap, Cloud cloud, String logicalId, Object config) {
		log.debug("mapSecurityGroupLocation: {}, {}", appConfig, resourceMap)
		def rtn = [
				name: config?.Properties?.GroupName ?: logicalId,
				description: config?.Properties?.GroupDescription ?: ''
		] + resourceMap
		return rtn
	}

	static formatProvisionHostname(name) {
		def rtn = name
		try {
			if(rtn.indexOf('\$') < 0) {
				rtn = rtn.replaceAll(' ', '-')
				rtn = rtn.replaceAll('\'', '')
				rtn = rtn.replaceAll(',', '')
				rtn = rtn.toLowerCase()
			}
		} catch(e) {
			//ok
		}
		return rtn
	}


	//this should pull from the db - fallback to old mappings
	static findAwsResourceType(String apiType, MorpheusContext morpheusContext) {
		def accountResourceType = morpheusContext.async.accountResourceType.find(new DataQuery().withFilters([
				new DataFilter('category', resourceTypeCategory),
				new DataFilter('apiType', apiType)
		])).blockingGet()
		//findByCategoryAndApiType(resourceTypeCategory, apiType, [cache:true])
		if(!accountResourceType) {
			def typeList = apiType.tokenize('::')
			if(typeList?.size > 1) {
				//find the category
				def typeCategory = typeList[0] + '::' + typeList[1]
				def categoryMatch = awsResourceTypes.find{ it.apiType == typeCategory }
				if(categoryMatch) {
					//find the match
					accountResourceType = categoryMatch.resourceTypes?.find{ it.apiType == apiType }
				}
			}
		}
		return accountResourceType
	}



	static decodeResourceStatus(String status) {
		def rtn = 'unknown'
		switch(status) {
			case 'CREATE_IN_PROGRESS':
			case 'UPDATE_IN_PROGRESS':
				rtn = 'deploying'
				break
			case 'UPDATE_COMPLETE':
			case 'CREATE_COMPLETE':
				rtn = 'running'
				break
			case 'DELETE_FAILED':
			case 'CREATE_FAILED':
			case 'UPDATE_FAILED':
				rtn = 'failed'
				break
			case 'DELETE_COMPLETE':
				rtn = 'stopped'
				break
			case 'DELETE_IN_PROGRESS':
				rtn = 'stopping'
				break
			case 'DELETE_SKIPPED':
				rtn = 'warning'
				break
		}
		return rtn
	}


	static loadYamlOrJsonMap(String config) {
		def rtn = null
		//try yaml
		try {
			def yaml = new org.yaml.snakeyaml.Yaml()
			rtn = yaml.load(config)
		} catch(e) {
			//not yaml - skip
			log.info("Bad yaml encountered.. trying cloudformation: ${e}")
			rtn = null
		}
		//try yaml - cloud formation
		if(rtn == null) {
			try {
				def yaml = new org.yaml.snakeyaml.Yaml(new CloudFormationYamlConstructor())
				rtn = yaml.load(config)
			} catch (e) {
				//not yaml - skip
				log.info("Bad CloudFormation yaml encountered: ${e}")
				rtn = null
			}
		}
		if(rtn == null) {
			try {
				rtn = new groovy.json.JsonSlurper().parseText(config)
			} catch(e) {
				//not json - skip
			}
		}
		return rtn ?: [:]
	}

	static buildSpecVariableScript(String script, Map scriptConfig, Boolean escForJSON = false) {
		def rtn = script
		try {
			//todo - handle string too long
			def finalConfig = formatValuesForSsh(scriptConfig)
			if (escForJSON) {
				finalConfig = escapeValuesForJSON(finalConfig)
			}
			def engine = new groovy.text.SimpleTemplateEngine()
			def escapeScript = script.replace('$', '<MORPHD>')
			escapeScript = escapeScript.replace('\\n', '<MORPHN>')
			escapeScript = escapeScript.replace('\\t', '<MORPHT>')
			escapeScript = escapeScript.replace('\\', '<MORPHB>')
			def template = engine.createTemplate(escapeScript).make(finalConfig)
			rtn = template.toString()
			rtn = rtn.replace('<MORPHD>', '$')
			rtn = rtn.replace('<MORPHN>', '\\n')
			rtn = rtn.replace('<MORPHT>', '\\t')
			rtn = rtn.replace('<MORPHB>', '\\')
			log.debug("buildSpecVariableScript - output: ${rtn}")
		} catch(e) {
			log.error("buildSpecVariableScript error: ${e}", e)
			log.info("script: ${script} - config: ${scriptConfig}")
			rtn = script
		}
		return rtn
	}

	static Map formatValuesForSsh(Map input) {
		def newMap = [:]
		input.each { k, v ->
			if(k != 'agentInstallTerraform') {
				if(v instanceof CharSequence) {
					newMap[k] = v
					ESCAPE_SEQUENCES.each { ek, ev ->
						newMap[k] = newMap[k].replace(ek, ev)
					}
				} else if(v instanceof List) {
					def newList = []
					v.toArray().each { i ->
						if(i instanceof CharSequence) {
							def result = i
							ESCAPE_SEQUENCES.each { ek, ev ->
								result = result.replace(ek, ev)
							}
							newList << result
						} else if(i instanceof Map) {
							newList << formatValuesForSsh(i)
						} else {
							newList << i
						}
					}
					newMap[k] = newList
				} else if (v instanceof Map) {
					newMap[k] = formatValuesForSsh(v)
				} else {
					newMap[k] = v
				}
			} else {
				newMap[k] = v
			}
		}
		return newMap
	}

	private static getUniqueInstanceName(name, account, MorpheusContext morpheusContext) {
		def inc = 1
		def baseName = name
		while(!validateInstanceName(baseName, account, morpheusContext)) {
			baseName = name + "-${inc}"
			inc++
		}
		return baseName
	}

	private static validateInstanceName(instanceName, account, MorpheusContext morpheusContext) {
		if(instanceName.contains('sequence')) {
			return true
		}
		def foundInstance = morpheusContext.async.instance.find(new DataQuery().withFilters([
		        new DataFilter('name', instanceName),
				new DataFilter('account', account)
		])).blockingGet()
		if(!foundInstance) {
			return true
		}
		return false
	}


	static final Map ESCAPE_SEQUENCES = [
			'$':'\\$'
	]

	//mapping types
	static awsResourceTypes = [
		[type:'AmazonMQ', apiType:'AWS::AmazonMQ', enabled:true, resourceTypes:[
			[type:'Broker', apiType:'AWS::AmazonMQ::Broker', enabled:true, name:'', morpheusType:null],
			[type:'Configuration', apiType:'AWS::AmazonMQ::Configuration', enabled:true, name:'', morpheusType:null],
			[type:'ConfigurationAssociation', apiType:'AWS::AmazonMQ::ConfigurationAssociation', enabled:true, name:'', morpheusType:null],
		]],
		[type:'ApiGateway', apiType:'AWS::ApiGateway', enabled:true, resourceTypes:[
			[type:'Account', apiType:'AWS::ApiGateway::Account', enabled:true, name:'', morpheusType:null],
			[type:'ApiKey', apiType:'AWS::ApiGateway::ApiKey', enabled:true, name:'', morpheusType:null],
			[type:'Authorizer', apiType:'AWS::ApiGateway::Authorizer', enabled:true, name:'', morpheusType:null],
			[type:'BasePathMapping', apiType:'AWS::ApiGateway::BasePathMapping', enabled:true, name:'', morpheusType:null],
			[type:'ClientCertificate', apiType:'AWS::ApiGateway::ClientCertificate', enabled:true, name:'', morpheusType:null],
			[type:'Deployment', apiType:'AWS::ApiGateway::Deployment', enabled:true, name:'', morpheusType:null],
			[type:'DocumentationPart', apiType:'AWS::ApiGateway::DocumentationPart', enabled:true, name:'', morpheusType:null],
			[type:'DocumentationVersion', apiType:'AWS::ApiGateway::DocumentationVersion', enabled:true, name:'', morpheusType:null],
			[type:'DomainName', apiType:'AWS::ApiGateway::DomainName', enabled:true, name:'', morpheusType:null],
			[type:'GatewayResponse', apiType:'AWS::ApiGateway::GatewayResponse', enabled:true, name:'', morpheusType:null],
			[type:'Method', apiType:'AWS::ApiGateway::Method', enabled:true, name:'', morpheusType:null],
			[type:'Model', apiType:'AWS::ApiGateway::Model', enabled:true, name:'', morpheusType:null],
			[type:'RequestValidator', apiType:'AWS::ApiGateway::RequestValidator', enabled:true, name:'', morpheusType:null],
			[type:'Resource', apiType:'AWS::ApiGateway::Resource', enabled:true, name:'', morpheusType:null],
			[type:'RestApi', apiType:'AWS::ApiGateway::RestApi', enabled:true, name:'', morpheusType:null],
			[type:'Stage', apiType:'AWS::ApiGateway::Stage', enabled:true, name:'', morpheusType:null],
			[type:'UsagePlan', apiType:'AWS::ApiGateway::UsagePlan', enabled:true, name:'', morpheusType:null],
			[type:'UsagePlanKey', apiType:'AWS::ApiGateway::UsagePlanKey', enabled:true, name:'', morpheusType:null],
			[type:'VpcLink', apiType:'AWS::ApiGateway::VpcLink', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ApiGatewayV2', apiType:'AWS::ApiGatewayV2', enabled:true, resourceTypes:[
			[type:'Api', apiType:'AWS::ApiGatewayV2::Api', enabled:true, name:'', morpheusType:null],
			[type:'ApiMapping', apiType:'AWS::ApiGatewayV2::ApiMapping', enabled:true, name:'', morpheusType:null],
			[type:'Authorizer', apiType:'AWS::ApiGatewayV2::Authorizer', enabled:true, name:'', morpheusType:null],
			[type:'Deployment', apiType:'AWS::ApiGatewayV2::Deployment', enabled:true, name:'', morpheusType:null],
			[type:'DomainName', apiType:'AWS::ApiGatewayV2::DomainName', enabled:true, name:'', morpheusType:null],
			[type:'Integration', apiType:'AWS::ApiGatewayV2::Integration', enabled:true, name:'', morpheusType:null],
			[type:'IntegrationResponse', apiType:'AWS::ApiGatewayV2::IntegrationResponse', enabled:true, name:'', morpheusType:null],
			[type:'Model', apiType:'AWS::ApiGatewayV2::Model', enabled:true, name:'', morpheusType:null],
			[type:'Route', apiType:'AWS::ApiGatewayV2::Route', enabled:true, name:'', morpheusType:null],
			[type:'RouteResponse', apiType:'AWS::ApiGatewayV2::RouteResponse', enabled:true, name:'', morpheusType:null],
			[type:'Stage', apiType:'AWS::ApiGatewayV2::Stage', enabled:true, name:'', morpheusType:null]
		]],
		[type:'AppMesh', apiType:'AWS::AppMesh', enabled:true, resourceTypes:[
			[type:'Mesh', apiType:'AWS::AppMesh::Mesh', enabled:true, name:'', morpheusType:null],
			[type:'Route', apiType:'AWS::AppMesh::Route', enabled:true, name:'', morpheusType:null],
			[type:'VirtualNode', apiType:'AWS::AppMesh::VirtualNode', enabled:true, name:'', morpheusType:null],
			[type:'VirtualRouter', apiType:'AWS::AppMesh::VirtualRouter', enabled:true, name:'', morpheusType:null],
			[type:'VirtualService', apiType:'AWS::AppMesh::VirtualService', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Athena', apiType:'AWS::Athena', enabled:true, resourceTypes:[
			[type:'NamedQuery', apiType:'AWS::Athena::NamedQuery', enabled:true, name:'', morpheusType:null],
		]],
		[type:'DynamoDB', apiType:'AWS::DynamoDB', enabled:true, resourceTypes:[
			[type:'Table', apiType:'AWS::DynamoDB::Table', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ECS', apiType:'AWS::ECS', enabled:true, resourceTypes:[
			[type:'Cluster', apiType:'AWS::ECS::Cluster', enabled:true, name:'', morpheusType:null],
			[type:'Service', apiType:'AWS::ECS::Service', enabled:true, name:'', morpheusType:null],
			[type:'TaskDefinition', apiType:'AWS::ECS::TaskDefinition', enabled:true, name:'', morpheusType:null]
		]],
		[type:'EFS', apiType:'AWS::EFS', enabled:true, resourceTypes:[
			[type:'FileSystem', apiType:'AWS::EFS::FileSystem', enabled:true, name:'', morpheusType:null],
			[type:'MountTarget', apiType:'AWS::EFS::MountTarget', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ElastiCache', apiType:'AWS::ElastiCache', enabled:true, resourceTypes:[
			[type:'CacheCluster', apiType:'AWS::ElastiCache::CacheCluster', enabled:true, name:'', morpheusType:null],
			[type:'ParameterGroup', apiType:'AWS::ElastiCache::ParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'ReplicationGroup', apiType:'AWS::ElastiCache::ReplicationGroup', enabled:true, name:'', morpheusType:null],
			[type:'SecurityGroup', apiType:'AWS::ElastiCache::SecurityGroup', enabled:true, name:'', morpheusType:null],
			[type:'SecurityGroupIngress', apiType:'AWS::ElastiCache::SecurityGroupIngress', enabled:true, name:'', morpheusType:null],
			[type:'SubnetGroup', apiType:'AWS::ElastiCache::SubnetGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'EKS', apiType:'AWS::EKS', enabled:true, resourceTypes:[
			[type:'Cluster', apiType:'AWS::EKS::Cluster', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ElasticBeanstalk', apiType:'AWS::ElasticBeanstalk', enabled:true, resourceTypes:[
			[type:'Application', apiType:'AWS::ElasticBeanstalk::Application', enabled:true, name:'', morpheusType:null],
			[type:'ApplicationVersion', apiType:'AWS::ElasticBeanstalk::ApplicationVersion', enabled:true, name:'', morpheusType:null],
			[type:'ConfigurationTemplate', apiType:'AWS::ElasticBeanstalk::ConfigurationTemplate', enabled:true, name:'', morpheusType:null],
			[type:'Environment', apiType:'AWS::ElasticBeanstalk::Environment', enabled:true, name:'', morpheusType:null]
		]],
		[type:'AutoScaling', apiType:'AWS::AutoScaling', enabled:true, resourceTypes:[
			[type:'AutoScalingGroup', apiType:'AWS::AutoScaling::AutoScalingGroup', enabled:true, name:'', morpheusType:null],
			[type:'LaunchConfiguration', apiType:'AWS::AutoScaling::LaunchConfiguration', enabled:true, name:'', morpheusType:null],
			[type:'LifecycleHook', apiType:'AWS::AutoScaling::LifecycleHook', enabled:true, name:'', morpheusType:null],
			[type:'ScalingPolicy', apiType:'AWS::AutoScaling::ScalingPolicy', enabled:true, name:'', morpheusType:null],
			[type:'ScheduledAction', apiType:'AWS::AutoScaling::ScheduledAction', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ElasticLoadBalancing', apiType:'AWS::ElasticLoadBalancing', enabled:true, resourceTypes:[
			[type:'LoadBalancer', apiType:'AWS::ElasticLoadBalancing::LoadBalancer', enabled:true, name:'', morpheusType:null]
		]],
		[type:'ElasticLoadBalancingV2', apiType:'AWS::ElasticLoadBalancingV2', enabled:true, resourceTypes:[
			[type:'Listener', apiType:'AWS::ElasticLoadBalancingV2::Listener', enabled:true, name:'', morpheusType:null],
			[type:'ListenerCertificate', apiType:'AWS::ElasticLoadBalancingV2::ListenerCertificate', enabled:true, name:'', morpheusType:null],
			[type:'ListenerRule', apiType:'AWS::ElasticLoadBalancingV2::ListenerRule', enabled:true, name:'', morpheusType:null],
			[type:'LoadBalancer', apiType:'AWS::ElasticLoadBalancingV2::LoadBalancer', enabled:true, name:'', morpheusType:null],
			[type:'TargetGroup', apiType:'AWS::ElasticLoadBalancingV2::TargetGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'EMR', apiType:'AWS::EMR', enabled:true, resourceTypes:[
			[type:'Cluster', apiType:'AWS::EMR::Cluster', enabled:true, name:'', morpheusType:null],
			[type:'InstanceFleetConfig', apiType:'AWS::EMR::InstanceFleetConfig', enabled:true, name:'', morpheusType:null],
			[type:'InstanceGroupConfig', apiType:'AWS::EMR::InstanceGroupConfig', enabled:true, name:'', morpheusType:null],
			[type:'SecurityConfiguration', apiType:'AWS::EMR::SecurityConfiguration', enabled:true, name:'', morpheusType:null],
			[type:'Step', apiType:'AWS::EMR::Step', enabled:true, name:'', morpheusType:null]
		]],
		[type:'IAM', apiType:'AWS::IAM', enabled:true, resourceTypes:[
			[type:'AccessKey', apiType:'AWS::IAM::AccessKey', enabled:true, name:'', morpheusType:null],
			[type:'Group', apiType:'AWS::IAM::Group', enabled:true, name:'', morpheusType:null],
			[type:'InstanceProfile', apiType:'AWS::IAM::InstanceProfile', enabled:true, name:'', morpheusType:null],
			[type:'ManagedPolicy', apiType:'AWS::IAM::ManagedPolicy', enabled:true, name:'', morpheusType:null],
			[type:'Policy', apiType:'AWS::IAM::Policy', enabled:true, name:'', morpheusType:null],
			[type:'Role', apiType:'AWS::IAM::Role', enabled:true, name:'', morpheusType:null],
			[type:'ServiceLinkedRole', apiType:'AWS::IAM::ServiceLinkedRole', enabled:true, name:'', morpheusType:null],
			[type:'User', apiType:'AWS::IAM::User', enabled:true, name:'', morpheusType:null],
			[type:'UserToGroupAddition', apiType:'AWS::IAM::UserToGroupAddition', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Kinesis', apiType:'AWS::Kinesis', enabled:true, resourceTypes:[
			[type:'Stream', apiType:'AWS::Kinesis::Stream', enabled:true, name:'', morpheusType:null],
			[type:'StreamConsumer', apiType:'AWS::Kinesis::StreamConsumer', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Lambda', apiType:'AWS::Lambda', enabled:true, resourceTypes:[
			[type:'Alias', apiType:'AWS::Lambda::Alias', enabled:true, name:'', morpheusType:null],
			[type:'EventSourceMapping', apiType:'AWS::Lambda::EventSourceMapping', enabled:true, name:'', morpheusType:null],
			[type:'Function', apiType:'AWS::Lambda::Function', enabled:true, name:'', morpheusType:null],
			[type:'LayerVersion', apiType:'AWS::Lambda::LayerVersion', enabled:true, name:'', morpheusType:null],
			[type:'LayerVersionPermission', apiType:'AWS::Lambda::LayerVersionPermission', enabled:true, name:'', morpheusType:null],
			[type:'Permission', apiType:'AWS::Lambda::Permission', enabled:true, name:'', morpheusType:null],
			[type:'Version', apiType:'AWS::Lambda::Version', enabled:true, name:'', morpheusType:null]
		]],
		[type:'RDS', apiType:'AWS::RDS', enabled:true, resourceTypes:[
			[type:'DBCluster', apiType:'AWS::RDS::DBCluster', enabled:true, name:'', morpheusType:null],
			[type:'DBClusterParameterGroup', apiType:'AWS::RDS::DBClusterParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'DBInstance', apiType:'AWS::RDS::DBInstance', enabled:true, name:'', morpheusType:null],
			[type:'DBParameterGroup', apiType:'AWS::RDS::DBParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'DBSecurityGroup', apiType:'AWS::RDS::DBSecurityGroup', enabled:true, name:'', morpheusType:null],
			[type:'DBSecurityGroupIngress', apiType:'AWS::RDS::DBSecurityGroupIngress', enabled:true, name:'', morpheusType:null],
			[type:'DBSubnetGroup', apiType:'AWS::RDS::DBSubnetGroup', enabled:true, name:'', morpheusType:null],
			[type:'EventSubscription', apiType:'AWS::RDS::EventSubscription', enabled:true, name:'', morpheusType:null],
			[type:'OptionGroup', apiType:'AWS::RDS::OptionGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Redshift', apiType:'AWS::Redshift', enabled:true, resourceTypes:[
			[type:'Cluster', apiType:'AWS::Redshift::Cluster', enabled:true, name:'', morpheusType:null],
			[type:'ClusterParameterGroup', apiType:'AWS::Redshift::ClusterParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'ClusterSecurityGroup', apiType:'AWS::Redshift::ClusterSecurityGroup', enabled:true, name:'', morpheusType:null],
			[type:'ClusterSecurityGroupIngress', apiType:'AWS::Redshift::ClusterSecurityGroupIngress', enabled:true, name:'', morpheusType:null],
			[type:'ClusterSubnetGroup', apiType:'AWS::Redshift::ClusterSubnetGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Neptune', apiType:'AWS::Neptune', enabled:true, resourceTypes:[
			[type:'DBCluster', apiType:'AWS::Neptune::DBCluster', enabled:true, name:'', morpheusType:null],
			[type:'DBClusterParameterGroup', apiType:'AWS::Neptune::DBClusterParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'DBInstance', apiType:'AWS::Neptune::DBInstance', enabled:true, name:'', morpheusType:null],
			[type:'DBParameterGroup', apiType:'AWS::Neptune::DBParameterGroup', enabled:true, name:'', morpheusType:null],
			[type:'DBSubnetGroup', apiType:'AWS::Neptune::DBSubnetGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Route53', apiType:'AWS::Route53', enabled:true, resourceTypes:[
			[type:'HealthCheck', apiType:'AWS::Route53::HealthCheck', enabled:true, name:'', morpheusType:null],
			[type:'HostedZone', apiType:'AWS::Route53::HostedZone', enabled:true, name:'', morpheusType:null],
			[type:'RecordSet', apiType:'AWS::Route53::RecordSet', enabled:true, name:'', morpheusType:null],
			[type:'RecordSetGroup', apiType:'AWS::Route53::RecordSetGroup', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Route53Resolver', apiType:'AWS::Route53Resolver', enabled:true, resourceTypes:[
			[type:'ResolverEndpoint', apiType:'AWS::Route53Resolver::ResolverEndpoint', enabled:true, name:'', morpheusType:null],
			[type:'ResolverRule', apiType:'AWS::Route53Resolver::ResolverRule', enabled:true, name:'', morpheusType:null],
			[type:'ResolverRuleAssociation', apiType:'AWS::Route53Resolver::ResolverRuleAssociation', enabled:true, name:'', morpheusType:null]
		]],
		[type:'S3', apiType:'AWS::S3', enabled:true, resourceTypes:[
			[type:'Bucket', apiType:'AWS::S3::Bucket', enabled:true, name:'', morpheusType:null],
			[type:'BucketPolicy', apiType:'AWS::S3::BucketPolicy', enabled:true, name:'', morpheusType:null]
		]],
		[type:'SecretsManager', apiType:'AWS::SecretsManager', enabled:true, resourceTypes:[
			[type:'ResourcePolicy', apiType:'AWS::SecretsManager::ResourcePolicy', enabled:true, name:'', morpheusType:null],
			[type:'RotationSchedule', apiType:'AWS::SecretsManager::RotationSchedule', enabled:true, name:'', morpheusType:null],
			[type:'Secret', apiType:'AWS::SecretsManager::Secret', enabled:true, name:'', morpheusType:null],
			[type:'SecretTargetAttachment', apiType:'AWS::SecretsManager::SecretTargetAttachment', enabled:true, name:'', morpheusType:null]
		]],
		[type:'SES', apiType:'AWS::SES', enabled:true, resourceTypes:[
			[type:'ConfigurationSet', apiType:'AWS::SES::ConfigurationSet', enabled:true, name:'', morpheusType:null],
			[type:'ConfigurationSetEventDestination', apiType:'AWS::SES::ConfigurationSetEventDestination', enabled:true, name:'', morpheusType:null],
			[type:'ReceiptFilter', apiType:'AWS::SES::ReceiptFilter', enabled:true, name:'', morpheusType:null],
			[type:'ReceiptRule', apiType:'AWS::SES::ReceiptRule', enabled:true, name:'', morpheusType:null],
			[type:'ReceiptRuleSet', apiType:'AWS::SES::ReceiptRuleSet', enabled:true, name:'', morpheusType:null],
			[type:'Template', apiType:'AWS::SES::Template', enabled:true, name:'', morpheusType:null]
		]],
		[type:'SDB', apiType:'AWS::SDB', enabled:true, resourceTypes:[
			[type:'Domain', apiType:'AWS::SDB::Domain', enabled:true, name:'', morpheusType:null]
		]],
		[type:'SNS', apiType:'AWS::SNS', enabled:true, resourceTypes:[
			[type:'Subscription', apiType:'AWS::SNS::Subscription', enabled:true, name:'', morpheusType:null],
			[type:'Topic', apiType:'AWS::SNS::Topic', enabled:true, name:'', morpheusType:null],
			[type:'TopicPolicy', apiType:'AWS::SNS::TopicPolicy', enabled:true, name:'', morpheusType:null]
		]],
		[type:'SQS', apiType:'AWS::SQS', enabled:true, resourceTypes:[
			[type:'Queue', apiType:'AWS::SQS::Queue', enabled:true, name:'', morpheusType:null],
			[type:'QueuePolicy', apiType:'AWS::SQS::QueuePolicy', enabled:true, name:'', morpheusType:null]
		]],
		[type:'WAF', apiType:'AWS::WAF', enabled:true, resourceTypes:[
			[type:'ByteMatchSet', apiType:'AWS::WAF::ByteMatchSet', enabled:true, name:'', morpheusType:null],
			[type:'IPSet', apiType:'AWS::WAF::IPSet', enabled:true, name:'', morpheusType:null],
			[type:'Rule', apiType:'AWS::WAF::Rule', enabled:true, name:'', morpheusType:null],
			[type:'SizeConstraintSet', apiType:'AWS::WAF::SizeConstraintSet', enabled:true, name:'', morpheusType:null],
			[type:'SqlInjectionMatchSet', apiType:'AWS::WAF::SqlInjectionMatchSet', enabled:true, name:'', morpheusType:null],
			[type:'WebACL', apiType:'AWS::WAF::WebACL', enabled:true, name:'', morpheusType:null],
			[type:'XssMatchSet', apiType:'AWS::WAF::XssMatchSet', enabled:true, name:'', morpheusType:null]
		]],
		[type:'WAFRegional', apiType:'AWS::WAFRegional', enabled:true, resourceTypes:[
			[type:'ByteMatchSet', apiType:'AWS::WAFRegional::ByteMatchSet', enabled:true, name:'', morpheusType:null],
			[type:'GeoMatchSet', apiType:'AWS::WAFRegional::GeoMatchSet', enabled:true, name:'', morpheusType:null],
			[type:'IPSet', apiType:'AWS::WAFRegional::IPSet', enabled:true, name:'', morpheusType:null],
			[type:'RateBasedRule', apiType:'AWS::WAFRegional::RateBasedRule', enabled:true, name:'', morpheusType:null],
			[type:'RegexPatternSet', apiType:'AWS::WAFRegional::RegexPatternSet', enabled:true, name:'', morpheusType:null],
			[type:'Rule', apiType:'AWS::WAFRegional::Rule', enabled:true, name:'', morpheusType:null],
			[type:'SizeConstraintSet', apiType:'AWS::WAFRegional::SizeConstraintSet', enabled:true, name:'', morpheusType:null],
			[type:'SqlInjectionMatchSet', apiType:'AWS::WAFRegional::SqlInjectionMatchSet', enabled:true, name:'', morpheusType:null],
			[type:'WebACL', apiType:'AWS::WAFRegional::WebACL', enabled:true, name:'', morpheusType:null],
			[type:'WebACLAssociation', apiType:'AWS::WAFRegional::WebACLAssociation', enabled:true, name:'', morpheusType:null],
			[type:'XssMatchSet', apiType:'AWS::WAFRegional::XssMatchSet', enabled:true, name:'', morpheusType:null]
		]],
		[type:'WorkSpaces', apiType:'AWS::WorkSpaces', enabled:true, resourceTypes:[
			[type:'Workspace', apiType:'AWS::WorkSpaces::Workspace', enabled:true, name:'', morpheusType:null]
		]],
		[type:'EC2', apiType:'AWS::EC2', enabled:true, resourceTypes:[
			[type:'CapacityReservation', apiType:'AWS::EC2::CapacityReservation', enabled:true, name:'', morpheusType:null],
			[type:'CapacityReservation', apiType:'AWS::EC2::CapacityReservation', enabled:true, name:'', morpheusType:null],
			[type:'ClientVpnAuthorizationRule', apiType:'AWS::EC2::ClientVpnAuthorizationRule', enabled:true, name:'', morpheusType:null],
			[type:'ClientVpnEndpoint', apiType:'AWS::EC2::ClientVpnEndpoint', enabled:true, name:'', morpheusType:null],
			[type:'ClientVpnRoute', apiType:'AWS::EC2::ClientVpnRoute', enabled:true, name:'', morpheusType:null],
			[type:'ClientVpnTargetNetworkAssociation', apiType:'AWS::EC2::ClientVpnTargetNetworkAssociation', enabled:true, name:'', morpheusType:null],
			[type:'CustomerGateway', apiType:'AWS::EC2::CustomerGateway', enabled:true, name:'', morpheusType:null],
			[type:'DHCPOptions', apiType:'AWS::EC2::DHCPOptions', enabled:true, name:'', morpheusType:null],
			[type:'EC2Fleet', apiType:'AWS::EC2::EC2Fleet', enabled:true, name:'', morpheusType:null],
			[type:'EgressOnlyInternetGateway', apiType:'AWS::EC2::EgressOnlyInternetGateway', enabled:true, name:'', morpheusType:null],
			[type:'EIP', apiType:'AWS::EC2::EIP', enabled:true, name:'', morpheusType:null],
			[type:'EIPAssociation', apiType:'AWS::EC2::EIPAssociation', enabled:true, name:'', morpheusType:null],
			[type:'FlowLog', apiType:'AWS::EC2::FlowLog', enabled:true, name:'', morpheusType:null],
			[type:'Host', apiType:'AWS::EC2::Host', enabled:true, name:'', morpheusType:null],
			[type:'Instance', apiType:'AWS::EC2::Instance', enabled:true, name:'', morpheusType:'instance'],
			[type:'InternetGateway', apiType:'AWS::EC2::InternetGateway', enabled:true, name:'', morpheusType:null],
			[type:'LaunchTemplate', apiType:'AWS::EC2::LaunchTemplate', enabled:true, name:'', morpheusType:null],
			[type:'NatGateway', apiType:'AWS::EC2::NatGateway', enabled:true, name:'', morpheusType:null],
			[type:'NetworkAcl', apiType:'AWS::EC2::NetworkAcl', enabled:true, name:'', morpheusType:null],
			[type:'NetworkAclEntry', apiType:'AWS::EC2::NetworkAclEntry', enabled:true, name:'', morpheusType:null],
			[type:'NetworkInterface', apiType:'AWS::EC2::NetworkInterface', enabled:true, name:'', morpheusType:null],
			[type:'NetworkInterfaceAttachment', apiType:'AWS::EC2::NetworkInterfaceAttachment', enabled:true, name:'', morpheusType:null],
			[type:'NetworkInterfacePermission', apiType:'AWS::EC2::NetworkInterfacePermission', enabled:true, name:'', morpheusType:null],
			[type:'PlacementGroup', apiType:'AWS::EC2::PlacementGroup', enabled:true, name:'', morpheusType:null],
			[type:'Route', apiType:'AWS::EC2::Route', enabled:true, name:'', morpheusType:null],
			[type:'RouteTable', apiType:'AWS::EC2::RouteTable', enabled:true, name:'', morpheusType:null],
			[type:'SecurityGroup', apiType:'AWS::EC2::SecurityGroup', enabled:true, name:'', morpheusType:'networkSecurityGroup'],
			[type:'SecurityGroupEgress', apiType:'AWS::EC2::SecurityGroupEgress', enabled:true, name:'', morpheusType:null],
			[type:'SecurityGroupIngress', apiType:'AWS::EC2::SecurityGroupIngress', enabled:true, name:'', morpheusType:null],
			[type:'SpotFleet', apiType:'AWS::EC2::SpotFleet', enabled:true, name:'', morpheusType:null],
			[type:'Subnet', apiType:'AWS::EC2::Subnet', enabled:true, name:'', morpheusType:null],
			[type:'SubnetCidrBlock', apiType:'AWS::EC2::SubnetCidrBlock', enabled:true, name:'', morpheusType:null],
			[type:'SubnetNetworkAclAssociation', apiType:'AWS::EC2::SubnetNetworkAclAssociation', enabled:true, name:'', morpheusType:null],
			[type:'SubnetRouteTableAssociation', apiType:'AWS::EC2::SubnetRouteTableAssociation', enabled:true, name:'', morpheusType:null],
			[type:'TransitGateway', apiType:'AWS::EC2::TransitGateway', enabled:true, name:'', morpheusType:null],
			[type:'TransitGatewayAttachment', apiType:'AWS::EC2::TransitGatewayAttachment', enabled:true, name:'', morpheusType:null],
			[type:'TransitGatewayRoute', apiType:'AWS::EC2::TransitGatewayRoute', enabled:true, name:'', morpheusType:null],
			[type:'TransitGatewayRouteTable', apiType:'AWS::EC2::TransitGatewayRouteTable', enabled:true, name:'', morpheusType:null],
			[type:'TransitGatewayRouteTableAssociation', apiType:'AWS::EC2::TransitGatewayRouteTableAssociation', enabled:true, name:'', morpheusType:null],
			[type:'TransitGatewayRouteTablePropagation', apiType:'AWS::EC2::TransitGatewayRouteTablePropagation', enabled:true, name:'', morpheusType:null],
			[type:'Volume', apiType:'AWS::EC2::Volume', enabled:true, name:'', morpheusType:null],
			[type:'VolumeAttachment', apiType:'AWS::EC2::VolumeAttachment', enabled:true, name:'', morpheusType:null],
			[type:'VPC', apiType:'AWS::EC2::VPC', enabled:true, name:'', morpheusType:'computeZonePool'],
			[type:'VPCCidrBlock', apiType:'AWS::EC2::VPCCidrBlock', enabled:true, name:'', morpheusType:'network'],
			[type:'VPCDHCPOptionsAssociation', apiType:'AWS::EC2::VPCDHCPOptionsAssociation', enabled:true, name:'', morpheusType:null],
			[type:'VPCEndpoint', apiType:'AWS::EC2::VPCEndpoint', enabled:true, name:'', morpheusType:null],
			[type:'VPCEndpointConnectionNotification', apiType:'AWS::EC2::VPCEndpointConnectionNotification', enabled:true, name:'', morpheusType:null],
			[type:'VPCEndpointService', apiType:'AWS::EC2::VPCEndpointService', enabled:true, name:'', morpheusType:null],
			[type:'VPCEndpointServicePermissions', apiType:'AWS::EC2::VPCEndpointServicePermissions', enabled:true, name:'', morpheusType:null],
			[type:'VPCGatewayAttachment', apiType:'AWS::EC2::VPCGatewayAttachment', enabled:true, name:'', morpheusType:null],
			[type:'VPCPeeringConnection', apiType:'AWS::EC2::VPCPeeringConnection', enabled:true, name:'', morpheusType:null],
			[type:'VPNConnection', apiType:'AWS::EC2::VPNConnection', enabled:true, name:'', morpheusType:null],
			[type:'VPNConnectionRoute', apiType:'AWS::EC2::VPNConnectionRoute', enabled:true, name:'', morpheusType:null],
			[type:'VPNGateway', apiType:'AWS::EC2::VPNGateway', enabled:true, name:'', morpheusType:null],
			[type:'VPNGatewayRoutePropagation', apiType:'AWS::EC2::VPNGatewayRoutePropagation', enabled:true, name:'', morpheusType:null]
		]],
		[type:'Elasticsearch', apiType:'AWS::Elasticsearch', enabled:true, resourceTypes:[
			[type:'Domain', apiType:'AWS::Elasticsearch::Domain', enabled:true, name:'', morpheusType:null]
		]]
	]

}
