package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.IpPermission
import com.bertramlabs.plugins.karman.network.NetworkProvider
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.SecurityGroupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZonePool
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.response.ServiceResponse
import com.amazonaws.services.ec2.model.SecurityGroup as AWSSecurityGroup
import groovy.util.logging.Slf4j

import java.security.MessageDigest

@Slf4j
class AWSSecurityGroupProvider implements SecurityGroupProvider {

	AWSPlugin plugin
	MorpheusContext morpheusContext

	AWSSecurityGroupProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return plugin
	}

	@Override
	String getCode() {
		return "amazon-security-group"
	}

	@Override
	String getName() {
		return "Amazon Security Group"
	}

	@Override
	ServiceResponse<SecurityGroup> prepareSecurityGroup(SecurityGroup securityGroup, Map opts) {
		def vpcId = opts.vpc ?: opts.vpcId ?: opts.config?.vpc ?: opts.config?.vpcId
		securityGroup.setConfigProperty("vpcId", vpcId)

		return ServiceResponse.success(securityGroup)
	}

	@Override
	ServiceResponse validateSecurityGroup(SecurityGroup securityGroup, Map opts) {
		ServiceResponse rtn = ServiceResponse.create([success: true])
		def vpcId = securityGroup.getConfigProperty("vpcId")

		if(!vpcId) {
			rtn.success = false
			rtn.addError('vpc', morpheus.services.localization.get('default.blank.message', ['VPC']))
		}

		return rtn
	}

	@Override
	ServiceResponse<SecurityGroupLocation> createSecurityGroup(SecurityGroup securityGroup, Map opts) {
		log.debug("createSecurityGroup: {}", securityGroup)
		def rtn = ServiceResponse.prepare()
		try {
			Cloud cloud = morpheus.async.cloud.getCloudById(securityGroup.zoneId).blockingGet()
			def vpcId = securityGroup.getConfigProperty("vpcId")
			def vpc = morpheus.async.cloud.pool.listByCloudAndExternalIdIn(cloud.id, [vpcId]).toList().blockingGet()?.getAt(0)
			log.debug("createSecurityGroup vpc: ${vpc?.id}")
			AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, vpc?.regionCode)
			def securityGroupConfig = [
			    name: securityGroup.name,
				description: securityGroup.description ?: securityGroup.name,
				vpcId: vpcId
			]
			log.debug("securityGroup config: {}", securityGroupConfig)
			def apiResults = AmazonComputeUtility.createSecurityGroup(opts + [amazonClient: amazonClient, config: securityGroupConfig])
			log.debug("securityGroup apiResults: {}", apiResults)
			//create it
			if(apiResults?.success && apiResults.data.externalId) {
				def awsSecurityGroupResult = AmazonComputeUtility.getSecurityGroup([amazonClient: amazonClient, externalId: apiResults.data.externalId])
				log.debug("Load sg result: ${awsSecurityGroupResult}")
				if(awsSecurityGroupResult.success && awsSecurityGroupResult.securityGroup) {
					rtn.data = new SecurityGroupLocation(
						securityGroup: securityGroup,
						externalId: awsSecurityGroupResult.securityGroup.groupId,
						zonePool: vpc,
						regionCode: vpc.regionCode,
						ruleHash: getGroupRuleHash(awsSecurityGroupResult.securityGroup)
					)

					rtn.success = true
				}
			} else {
				rtn.success = false
				rtn.data = null
				rtn.msg = apiResults.msg
			}
		} catch(e) {
			rtn.msg = "Error creating Security Group in Amazon: ${e.message}"
			log.error "Error in createSecurityGroup: ${e}", e
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse<SecurityGroup> updateSecurityGroup(SecurityGroup securityGroup, Map opts) {
		log.warn("Modifying the name or description for a security group is not allowed by AWS")
		return ServiceResponse.success(securityGroup)
	}

	@Override
	ServiceResponse deleteSecurityGroupLocation(SecurityGroupLocation securityGroupLocation) {
		log.debug("deleteSecurityGroupLocation: {}", securityGroupLocation)
		def rtn = ServiceResponse.prepare()
		try {
			if(securityGroupLocation.externalId) {
				Cloud cloud = morpheus.async.cloud.getCloudById(securityGroupLocation.refId).blockingGet()
				AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, securityGroupLocation.zonePool?.regionCode)
				Map securityGroupResults = AmazonComputeUtility.deleteSecurityGroup([amazonClient: amazonClient, groupId: securityGroupLocation.externalId])
				if(securityGroupResults.success) {
					rtn.success = true
				}
			}
		} catch(e) {
			log.error("Error in deleteSecurityGroupLocation: ${e}", e)
		}
		return rtn
	}

	static String getGroupRuleHash(AWSSecurityGroup cloudItem) {
		MessageDigest md = MessageDigest.getInstance("MD5")
		md.update(getGroupRules(cloudItem).toString().bytes)
		byte[] checksum = md.digest()
		checksum.encodeHex().toString()
	}

	static List getGroupRules(AWSSecurityGroup cloudItem) {
		List rules = []

		['ingress': cloudItem.ipPermissions, 'egress': cloudItem.ipPermissionsEgress].each { direction, permissions ->
			for(IpPermission permission in permissions) {
				def ruleOptions = [
					direction: direction, ipProtocol: permission.ipProtocol, minPort: permission.fromPort, maxPort: permission.toPort
				]
				def ranges = permission.ipv4Ranges
				def userIdGroupPairs = permission.userIdGroupPairs
				if (ranges || userIdGroupPairs) {
					ranges?.each { range ->
						rules << ruleOptions + [description: range.description, ipRange: range.cidrIp]
					}
					userIdGroupPairs?.each { groupPair ->
						rules << ruleOptions + [description: groupPair.description, targetGroupId: groupPair.groupId, targetGroupName: groupPair.groupName]
					}
				} else {
					rules << ruleOptions
				}
			}
		}
		rules
	}
}
