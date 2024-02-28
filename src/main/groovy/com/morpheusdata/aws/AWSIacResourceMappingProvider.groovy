package com.morpheusdata.aws

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.IacResourceMappingProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.*
import com.morpheusdata.response.InstanceResourceMappingResponse
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.response.WorkloadResourceMappingResponse

class AWSIacResourceMappingProvider implements IacResourceMappingProvider {

	AWSPlugin plugin
	MorpheusContext morpheusContext

    AWSIacResourceMappingProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return 'aws-iac-resource-mapping-provider'
	}

	@Override
	String getName() {
		return "AWS IaC Resource Mapping Provider"
	}

	@Override
	List<String> getIacProvisionTypeCodes() {
		['terraform']
	}

	@Override
	ServiceResponse<InstanceResourceMappingResponse> resolveInstance(Instance instance, AccountResource resource, Map resourceResult, String iacProvider, String iacProviderType, String iacType) {
		//only supports terraform at the moment
		if (iacProvider == 'terraform') {
			InstanceResourceMappingResponse response = new InstanceResourceMappingResponse()
			def externalId = resourceResult?.values?.id
			def privateIp = resourceResult?.values?.private_ip
			def publicIp = resourceResult?.values?.public_ip
			def nameTag = resourceResult?.values?.tags ? resourceResult.values.tags['Name'] : null
			def serverName = nameTag ?: resourceResult?.name
			def arn = resourceResult?.values?.arn
			response.privateIp = privateIp
			response.publicIp = publicIp
			response.noAgent = true
			response.installAgent = false
			def serverList = instance.containers?.collect{ it.server }
			def serverMatch = externalId ? serverList?.find{ it.externalId == externalId } : null
			if(serverMatch == null && publicIp != null)
				serverMatch = serverList?.find{ it.externalIp == publicIp }
			if(serverMatch == null && privateIp != null)
				serverMatch = serverList?.find{ it.internalIp == privateIp }
			if(serverMatch == null && serverName != null)
				serverMatch = serverList?.find{ it.name == serverName }
			if(serverMatch == null)
				serverMatch = serverList?.find{ it.internalIp == null && it.externalIp == null && it.externalId == null }
			if(serverName && instance.displayName != serverName) {
				instance.name = serverName
				instance.displayName = serverName
			}
			if(serverMatch) {
				serverMatch.externalId = externalId
				if(serverName && serverMatch.name != serverName)
					serverMatch.name = serverName
				serverMatch.computeServerType = new ComputeServerType(code: 'amazonUnmanaged')
				//find the cloud in case different than selected?
				Cloud targetCloud = findSubnetCloud(resource.owner, resourceResult.values?.subnet_id)
				def regionCode = null
				if(arn) {
					regionCode = arn.toString().split(":")[3]
				}
				if(targetCloud) {
					serverMatch.cloud = targetCloud
					//set extra config
					instance.instanceTypeCode = 'amazon'
					instance.layout = new InstanceTypeLayout(code:'amazon-1.0-single')
					def workloads = morpheusContext.async.workload.listById(instance.containers.collect {it.id}).toList().blockingGet()
					workloads?.each { row ->
						row.workloadType = new WorkloadType(code: 'amazon-1.0')
					}
					morpheusContext.async.workload.save(workloads).blockingGet()
					//set user data
					def resourceConfig = resource?.getConfigMap() ?: [:]
					def userData = resourceConfig?.userData ?: [found: false]
					//configure for agent install
					if (userData?.found == true) {
						response.installAgent = true
						response.noAgent = false
					}
					//find a matching image and configure agent etc...
					if(resourceResult?.values?.ami) {
						def imageId = resourceResult.values.ami
						//save the ami
						def amazonClient = plugin.getAmazonClient(targetCloud, false, regionCode)
						def imageResults = EC2ProvisionProvider.saveAccountImage(amazonClient, resource.owner, targetCloud, regionCode, imageId, morpheusContext)
						if(imageResults.image) {
							serverMatch.sourceImage = imageResults.image
							serverMatch.serverOs = imageResults.image.osType ?: OsType.findByCode('other.64')
							serverMatch.osType = imageResults.image.platform
							serverMatch.platform = imageResults.image.platform
							//cs type
							def platform = serverMatch.sourceImage?.getPlatform()
							def csTypeCode = platform == 'windows' ? 'amazonWindowsVm' : 'amazonVm'
							serverMatch.computeServerType = new ComputeServerType(code: csTypeCode)
						}
					}

				}
				morpheusContext.async.computeServer.bulkSave([serverMatch]).blockingGet()
				morpheusContext.async.instance.save([instance]).blockingGet()
			}
			return ServiceResponse.success(response)
		} else {
			return ServiceResponse.error("IaC Provider ${iacProvider} not supported")
		}
	}

	@Override
	ServiceResponse<WorkloadResourceMappingResponse> resolveWorkload(Workload workload, AccountResource resource, Map resourceResult, String iacProvider, String iacProviderType, String iacType) {
		//only supports terraform at the moment
		if (iacProvider == 'terraform') {
			WorkloadResourceMappingResponse response = new WorkloadResourceMappingResponse()
			def externalId = resourceResult?.values?.id
			def privateIp = resourceResult?.values?.private_ip
			def publicIp = resourceResult?.values?.public_ip
			def arn = resourceResult?.values?.arn

			response.privateIp = privateIp
			response.publicIp = publicIp
			response.noAgent = true
			response.installAgent = false
			def nameTag = resourceResult?.values?.tags ? resourceResult.values.tags['Name'] : null
			def serverName = nameTag ?: resourceResult?.name
			def serverMatch = workload.server
			if(serverMatch) {
				serverMatch.externalId = externalId
				if(serverName && serverMatch.name != serverName)
					serverMatch.name = serverName
				serverMatch.computeServerType = new ComputeServerType(code: 'amazonUnmanaged')
				//find the cloud in case different than selected?
				Cloud targetCloud = findSubnetCloud(resource.owner, resourceResult.values?.subnet_id)
				def regionCode = null
				if(arn) {
					regionCode = arn.toString().split(":")[3]
				}
				if(targetCloud) {
					serverMatch.cloud = targetCloud
					serverMatch.region = new CloudRegion(code: regionCode)
					//set extra config
					workload.workloadType = workload.workloadType ?: new WorkloadType(code: 'amazon-1.0')

					//set user data
					def resourceConfig = resource?.getConfigMap() ?: [:]
					def userData = resourceConfig?.userData ?: [found: false]
					//configure for agent install
					if (userData?.found == true) {
						response.installAgent = true
						response.noAgent = false
					}
					//find a matching image and configure agent etc...
					if(resourceResult?.values?.ami) {
						def imageId = resourceResult.values.ami
						//save the ami
						def amazonClient = plugin.getAmazonClient(targetCloud, false, regionCode)
						def imageResults = EC2ProvisionProvider.saveAccountImage(amazonClient, resource.owner, targetCloud, regionCode, imageId, morpheusContext)
						if(imageResults.image) {
							serverMatch.sourceImage = imageResults.image
							serverMatch.serverOs = imageResults.image.osType ?: OsType.findByCode('other.64')
							serverMatch.osType = imageResults.image.platform
							serverMatch.platform = imageResults.image.platform
							//cs type
							def platform = serverMatch.sourceImage?.getPlatform()
							def csTypeCode = platform == 'windows' ? 'amazonWindowsVm' : 'amazonVm'
							serverMatch.computeServerType = new ComputeServerType(code: csTypeCode)
						}
					}

				}
				morpheusContext.async.computeServer.bulkSave([serverMatch]).blockingGet()
				morpheusContext.async.workload.save(workload).blockingGet()
			}
			return ServiceResponse.success(response)
		} else {
			return ServiceResponse.error("IaC Provider ${iacProvider} not supported")
		}

	}

	Cloud findSubnetCloud(Account account, String subnetId) {
		def rtn = null
		def subnet = morpheusContext.async.network.list(new DataQuery().withFilters(
				new DataFilter('owner', account),
				new DataFilter('refType', 'ComputeZone'),
				new DataFilter('refId', '!=', null),
				new DataFilter('externalId', subnetId)
		)).blockingFirst()
		if(subnet) {
			rtn = morpheusContext.async.cloud.get(subnet.refId).blockingGet()
		}
		return rtn
	}

}
