package com.morpheusdata.aws.utils

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Instance
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class CloudFormationResourceMappingUtility  {

	static transactional = false

	static resourceTypeCategory = 'aws.cloudFormation'

	def amazonComputeService


	static ServiceResponse parseResource(Instance instance, String content, Map scriptConfig, Map opts, MorpheusContext morpheusContext) {
		def appConfig = [name:instance.name, createdBy:instance.createdBy, account:instance.account,
						 serverGroup:instance.serverGroup, resourcePool:instance.resourcePool, site:instance.site,
						 plan:instance.plan, layout:instance.layout, instanceId:instance.id, instanceName:instance.name,
						 user:instance.createdBy, refType:'instance', refId:instance.id, refObj:instance,
						 refConfig:instance.getConfigMap()
		]
		//set the zone
		applyResourceCloud(appConfig, morpheusContext)
		//parse it
		return parseResource(appConfig, content, scriptConfig, opts, morpheusContext)
	}

	static def applyResourceCloud(Map appConfig, morpheusContext) {
		applyResourceCloud(appConfig, null, morpheusContext)
	}

	static def applyResourceCloud(Map appConfig, Map resource, MorpheusContext morpheusContext) {
		//set the default cloud
		if(appConfig.refObj) {
			if(appConfig.refType == 'instance') {
				//set the zone
				appConfig.defaultCloud = appConfig.refObj.provisionZoneId ? morpheusContext.async.cloud.getCloudById(appConfig.refObj.provisionZoneId).blockingGet() : null
			} else if(appConfig.refType == 'container') {
				//set the zone
				appConfig.defaultCloud = appConfig.refObj.resourcePool?.serverGroup?.zone ?: null
			} else if(appConfig.refType == 'app') {
				//get the cloud out of the config map inside the config map (odd i know)
				try {
					//check the ref config
					def baseConfig = appConfig.refConfig
					def userConfig = MorpheusUtils.getJson(baseConfig.config)
					if(userConfig.defaultCloud?.id) {
						appConfig.defaultCloud = morpheusContext.async.cloud.getCloudById(userConfig.defaultCloud.id.toLong()).blockingGet()
					} else {
						//check the ref objs config
						baseConfig = appConfig.refObj.getConfigMap()
						userConfig	= MorpheusUtils.getJson(baseConfig.config)
						if(userConfig.defaultCloud?.id)
							appConfig.defaultCloud = morpheusContext.async.cloud.getCloudById(userConfig.defaultCloud.id.toLong()).blockingGet()
					}
				} catch(e) {
					log.warn("error parsing default cloud from resource")
				}
			}
		}
		//assign it
		def resourceZone
		//if we have a resource
		if(resource) {
			if(resource.mapping) {
				//already mapped -
				resourceZone = resource.mapping.zone ?:
						appConfig.serverGroup?.zone ?:
								resource.mapping.provider?.computeZone ?:
										resource.zone ?:
												appConfig.defaultCloud
			} else {
				//not mapped
				resourceZone = appConfig.serverGroup?.zone ?:
						resource.zone ?:
								appConfig.defaultCloud
			}
		} else {
			//no resource
			resourceZone = appConfig.serverGroup?.zone ?:
					appConfig.defaultCloud
		}
		//pick one off the site?
		if(!resourceZone) {
			resourceZone = appConfig.site?.zones?.size() > 0 ? appConfig.site.zones.first() : null
		}
		//set it to the app config
		appConfig.zone = resourceZone
		//done
	}

	//splits a spec up into multiple resources and matches each up with a resource type
	static ServiceResponse parseResource(Map appConfig, String content, Map scriptConfig, Map opts, MorpheusContext morpheusContext) {
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
			def resourceParams = resourceConfig['Parameters']
			def resourceMappings = resourceConfig['Mappings']
			def resourceList = resourceConfig['Resources']
			def resourceOutputs = resourceConfig['Outputs']
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

	//adds provision info to resources
	ServiceResponse resolveResource(Map appConfig, Map resource, Collection resourceResults, Map opts) {
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
				resource.resource.save(flush:true)
				if(resource.resource.refType && resource.resource.refId) {
					switch(resource.resource.refType) {
						case 'instance':
							resolveInstance(appConfig, resource, matchResource, opts)
							break
						case 'container':
							resolveContainer(appConfig, resource, matchResource, opts)
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

	ServiceResponse resolveInstance(Map appConfig, Map resource, Object resourceResult, Map opts) {
		def rtn = [success:false, data:[:]]
		//resource: [id, morpheusType:'accountResource', type, name, resource]
		try {
			if(resource.resource.refId && resource.resource.externalId) {
				def instance = Instance.get(resource.resource.refId) 
				if(instance) {
					instance.externalId = resource.resource.externalId
					instance.save(flush:true)
				}
			}
		} catch(e) {
			log.error("error resolving instance: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	ServiceResponse resolveContainer(Map appConfig, Map resource, Object resourceResult, Map opts) {
		def rtn = [success:false, data:[:]]
		//resource: [id, morpheusType:'accountResource', type, name, resource]
		try {
			if(resource.resource.refId && resource.resource.externalId) {
				def container = Container.get(resource.resource.refId)
				if(container) {
					container.externalId = resource.resource.externalId
					if(container.server) {
						container.server.externalId = resource.resource.externalId

						// Fetch the ec2 instance and see if the tag name is set
						def serverResults = AmazonComputeUtility.getServerDetail([amazonClient: amazonComputeService.getAmazonClient(container.server.zone, false, container.server?.resourcePool?.regionCode ?: opts.regionCode), serverId: resource.resource.externalId])
						def nameTag = serverResults.success && serverResults.server ? serverResults.server.getTags()?.find { it.getKey()?.toLowerCase() == 'name' }?.getValue() : null
						if(nameTag && container.server.name != nameTag) {
							resource.resource.name = nameTag
							resource.resource.save()
							container.server.name = nameTag
							container.server.save()
						}
					}
					container.save(flush:true)
				}
			}
		} catch(e) {
			log.error("error resolving container: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	//set details from the cloud obj to the resources
	ServiceResponse updateResource(Map appConfig, Map resource, Map opts) {
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
				resource.resource.save(flush:true)
				rtn.success = true
			}
		} catch(e) {
			log.error("error updating resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	ServiceResponse updateResource(AccountResource resource, cloudResource) {
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
				resource.save(flush:true)
				rtn.success = true
			}
		} catch(e) {
			log.error("error updating resource: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	//app resource mapping for a list of specs
	static ServiceResponse mapResource(Map appConfig, Map resource, Map opts) {
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
								morpheusResults = mapContainer(appConfig, resource, opts)
							} else if(appConfig.refType == 'app')  { //if this is an app - create an instance
								morpheusResults = mapInstance(appConfig, resource, opts)
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
			rtn.msg = "error parsing cf resource: ${e}"
			log.error(rtn.msg, e)
		}
		return ServiceResponse.create(rtn)
	}

	//individual mappings - takes the map of input and mapped data returned by the map methods
	//  returns config for a morpheus object
	ServiceResponse mapInstance(Map appConfig, Map resource, Map opts) {
		def rtn = super.mapInstance(appConfig, resource, opts)
		//handle custom stuff for cf

		//done
		return ServiceResponse.create(rtn)
	}

	ServiceResponse mapContainer(Map appConfig, Map resource, Map opts) {
		appConfig.zone = getResourceZone(appConfig, resource)
		def rtn = super.mapContainer(appConfig, resource, opts)
		log.debug("map container: {}", resource)
		//handle custom stuff for cf
		def layoutConfig = [:]
		def templateInput = getTemplateParameters(appConfig)
		log.debug("templateInput {}", templateInput)
		def awsInstanceType = findAwsValue(resource.specMap, resource.resourceSpec, templateInput, 'InstanceType')
		def awsImageId = findAwsValue(resource.specMap, resource.resourceSpec, templateInput, 'ImageId')
		def awsKeyName = findAwsTypeValue(resource.specMap, templateInput, 'AWS::EC2::KeyPair::KeyName')
		rtn.data.resource.awsConfig = [instanceType:awsInstanceType, imageId:awsImageId, keyName:awsKeyName]
		//if the type is an ECS instance
		layoutConfig.plan = awsInstanceType ? ServicePlan.where{ code == 'amazon-' + awsInstanceType }.get(([cache:true])) : null
		layoutConfig.instanceType = InstanceType.where{ code == 'amazon' }.get([cache:true])
		layoutConfig.layout = InstanceTypeLayout.where{ code == 'amazon-1.0-single' }.get([cache:true])
		layoutConfig.computeServerType = ComputeServerType.where{ code == 'amazonVm' }.get([cache:true])
		layoutConfig.provisionType = layoutConfig.layout?.provisionType
		if(layoutConfig.layout?.containers?.size() > 0)
			layoutConfig.containerType = layoutConfig.layout.containers.first().containerType
		//set memory and cores - let the plan do that
		//load the aws image and get the platfrom and os?
		//layoutConfig.platform = 'unknown'
		//layoutConfig.osType = layoutConfig.osType ?: OsType.findByCode('other.64')
		rtn.data.resource.layoutConfig = layoutConfig
		//done
		return ServiceResponse.create(rtn)
	}

	ServiceResponse configureContainer(Map appConfig, Map resource, Map opts) {
		def rtn = super.configureContainer(appConfig, resource, opts)
		//nothing extra?
		if(rtn.data.output?.server && resource.awsConfig) {
			rtn.data.output.server.setConfigProperty('awsConfig', resource.awsConfig)
			rtn.data.output.server.save()
		}
		return ServiceResponse.create(rtn)
	}

	//helpers
	def getTemplateParameters(Map appConfig) {
		def rtn = appConfig.refConfig?.templateParameter ?: [:]
		rtn['AWS::StackName'] = appConfig.name
		//add the region from the zone
		def authConfig = appConfig.zone ? amazonComputeService.getAmazonAuthConfig(appConfig.zone) : null
		if(authConfig) {
			rtn['AWS::Region'] = authConfig.region
			//other items?
		}
		return rtn
	}

	def findAwsValue(Map resourceSpec, Map itemSpec, Map templateInput, String key) {
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

	def findAwsTypeValue(Map resourceSpec, Map templateInput, String type) {
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

	def processFunction(Map resourceSpec, Map functionMap, Map templateInput) {
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

	def processFindRef(Map resourceSpec, Object refItem, Map templateInput) {
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



	def decodeResourceStatus(String status) {
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
