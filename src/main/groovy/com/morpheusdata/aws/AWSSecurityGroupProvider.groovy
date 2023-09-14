package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.IpPermission
import com.amazonaws.services.ec2.model.IpRange
import com.amazonaws.services.ec2.model.Ipv6Range
import com.bertramlabs.plugins.karman.network.SecurityGroupRuleInterface
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.SecurityGroupProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.SecurityGroup
import com.morpheusdata.model.SecurityGroupLocation
import com.morpheusdata.model.SecurityGroupRule
import com.morpheusdata.model.SecurityGroupRuleLocation
import com.morpheusdata.response.ServiceResponse
import com.amazonaws.services.ec2.model.SecurityGroup as AWSSecurityGroup
import com.amazonaws.services.ec2.model.SecurityGroupRule as AWSSecurityGroupRule
import groovy.util.logging.Slf4j
import org.apache.commons.net.util.SubnetUtils

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
			rtn.success = false
			rtn.msg = "Error creating Security Group: ${e.message}"
			log.error "Error in createSecurityGroup: ${e}", e
		}
		return rtn
	}

	@Override
	ServiceResponse<SecurityGroup> updateSecurityGroup(SecurityGroup securityGroup, Map opts) {
		log.warn("Modifying the name or description for a security group is not allowed by AWS")
		return ServiceResponse.success(securityGroup)
	}

	@Override
	ServiceResponse deleteSecurityGroup(SecurityGroup securityGroup) {
		log.debug("deleteSecurityGroup: {}", securityGroup)
		def rtn = ServiceResponse.prepare()
		try {
			if(securityGroup.externalId) {
				Cloud cloud = morpheus.async.cloud.getCloudById(securityGroup.zoneId).blockingGet()
				def vpcId = securityGroup.getConfigProperty("vpcId")
				def vpc = morpheus.async.cloud.pool.listByCloudAndExternalIdIn(cloud.id, [vpcId]).toList().blockingGet()?.getAt(0)
				AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, vpc?.regionCode)
				Map securityGroupResults = AmazonComputeUtility.deleteSecurityGroup([amazonClient: amazonClient, groupId: securityGroup.externalId])
				if(securityGroupResults.success) {
					rtn.success = true
				}
			}
		} catch(e) {
			log.error("Error in deleteSecurityGroupLocation: ${e}", e)
		}
		return rtn
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

	@Override
	ServiceResponse<SecurityGroupRule> prepareSecurityGroupRule(SecurityGroupRule securityGroupRule, Map opts) {
		return ServiceResponse.success(securityGroupRule)
	}

	@Override
	ServiceResponse<SecurityGroupRule> validateSecurityGroupRule(SecurityGroupRule securityGroupRule) {
		log.debug("validateSecurityGroupRule: ${securityGroupRule}")
		def rtn = ServiceResponse.create([success: true])

		try {
			// From Amazon: The name must not begin with "sg-", and may contain the following characters: a-z, A-Z, 0-9, spaces, and ._-:/()#,@[]+=&;{}!$*
			def isValidName = (securityGroupRule.name =~ /^(?!sg-)([A-Z]|[a-z]|[0-9]|_| |-|:|#|,|@|=|&|\{|\}|!|\.|\)|\(|\]|\[|\+|\$|\*|\/|\;)+/).matches()
			if(!isValidName) {
				rtn.errors['name'] = morpheus.services.localization.get('gomorpheus.amazon.security.group.rule.name.validation.error', null)
			}
			rtn.success = !rtn.errors
		} catch(e) {
			log.error("Error in validateSecurityGroupRule: ${e}", e)
		}
		return rtn
	}

	@Override
	ServiceResponse<SecurityGroupRuleLocation> createSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule securityGroupRule) {
		log.debug("createSecurityGroupRule: {}, {}", securityGroupLocation, securityGroupRule)
		def rtn = ServiceResponse.prepare()
		try {
			if(securityGroupLocation.externalId && SecurityGroupRule) {
				Cloud cloud = morpheus.async.cloud.getCloudById(securityGroupLocation.refId).blockingGet()
				AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, securityGroupLocation.zonePool?.regionCode)

				def ruleConfig = assembleRuleConfig(securityGroupLocation, securityGroupRule)
				if(!ruleConfig.ipRange && !ruleConfig.targetGroupId) {
					log.warn("No ipRange OR targetGroupId for rule so not saving rule to Amazon")
				} else {
					def createResult = AmazonComputeUtility.createSecurityGroupRule([amazonClient: amazonClient, config: ruleConfig])
					if(createResult.success == true) {
						AWSSecurityGroupRule awsRule = (AWSSecurityGroupRule) createResult.rule
						def ruleLocation =  new SecurityGroupRuleLocation()
						ruleLocation.externalId = awsRule.securityGroupRuleId
						rtn.data = ruleLocation
					}
				}
				rtn.success = true
			}
		} catch(e) {
			log.error("Error in createSecurityGroupRule: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	@Override
	ServiceResponse<SecurityGroupRule> updateSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule originalRule, SecurityGroupRule updatedRule) {
		log.debug("updateSecurityGroupRule: {}, {}, {}", securityGroupLocation, originalRule, updatedRule)
		ServiceResponse rtn = ServiceResponse.prepare()

		try {
			// We can't reference a rule in Amazon by any sort of ID.  So... just delete the old and add the new (uses the actual configuration to locate the original rule)
			ServiceResponse deleteResults = deleteSecurityGroupRule(securityGroupLocation, originalRule)
			if(deleteResults.success == true) {
				log.debug("updateSecurityGroupRule: deleted rule, creating updated rule")
				ServiceResponse createResults = createSecurityGroupRule(securityGroupLocation, updatedRule)
				if(createResults.success == true) {
					updatedRule.externalId = createResults.data.externalId
					rtn.data = updatedRule
					rtn.success = true
				} else {
					rtn = createResults
				}
			} else {
				log.debug("Failed to delete rule during update: {}", deleteResults.msg)
				rtn = deleteResults
			}
		} catch(Exception e) {
			log.error("updateSecurityGroupRule error: {}", e)
		}

		return rtn
	}

	@Override
	ServiceResponse deleteSecurityGroupRule(SecurityGroupLocation securityGroupLocation, SecurityGroupRule securityGroupRule) {
		log.debug("deleteSecurityGroupRule: {}, {}", securityGroupLocation, securityGroupRule)
		def rtn = ServiceResponse.prepare()
		try {
			if(securityGroupLocation.externalId && securityGroupRule) {
				Cloud cloud = morpheus.async.cloud.getCloudById(securityGroupLocation.refId).blockingGet()
				AmazonEC2Client amazonClient = plugin.getAmazonClient(cloud, false, securityGroupLocation.zonePool?.regionCode)
				def ruleConfig = assembleRuleConfig(securityGroupLocation, securityGroupRule)
				if(!ruleConfig.ipRange && !ruleConfig.targetGroupId) {
					log.warn("Unable to delete rule: no ipRange OR targetGroupId defined")
				} else {
					def deleteResult = AmazonComputeUtility.deleteSecurityGroupRule([amazonClient: amazonClient, config: ruleConfig])
					if(deleteResult.success == true) {
						rtn.success = true
					} else {
						rtn.success = false
						rtn.msg = deleteResult.msg
					}
				}
			}
		} catch(e) {
			log.error("Error in deleteSecurityGroupRule: ${e}", e)
		}
		return ServiceResponse.create(rtn)
	}

	static assembleRuleConfig(SecurityGroupLocation securityGroupLocation, SecurityGroupRule rule) {
		def rtn = [:]
		rtn.securityGroupId = securityGroupLocation.externalId
		if(rule.name) {
			rtn.name = rule.name
			rtn.description = rule.name
		}
		def protocol = rule.protocol?.toLowerCase() ?: 'tcp'
		if(protocol) {
			if(protocol != 'any') {
				rtn.ipProtocol = protocol
			} else {
				rtn.ipProtocol = '-1'
			}

		}
		rtn.minPort = (rule.protocol?.toLowerCase() == 'icmp' ? -1 : rule.minPort)
		rtn.maxPort = (rule.protocol?.toLowerCase() == 'icmp' ? -1 : rule.maxPort)
		if(rule.source) {
			rtn.ipRange = []
			// TODO: Handle ipv6
			if(rule.source == '::/0') {
				rtn.ipRange << '0.0.0.0/0'
			} else {
				rtn.ipRange << rule.source
			}
		} else if(rule.destination) {
			rtn.ipRange = []
			// TODO: Handle ipv6
			if(rule.destination == '::/0') {
				rtn.ipRange << '0.0.0.0/0'
			} else {
				rtn.ipRange << rule.destination
			}

		}
		rtn.direction = rule.direction ?: 'ingress'
		if(rule.direction == 'ingress') {
			if(rule.sourceGroup) {
				def externalId = rule.sourceGroup.externalId ?: securityGroupLocation?.externalId
				if(externalId) {
					rtn.targetGroupId = externalId
				}
			}
		} else if(rule.destinationGroup) {
			def externalId = rule.destinationGroup.externalId ?: securityGroupLocation?.externalId
			if(externalId) {
				rtn.targetGroupId = externalId
			}
		}

		return rtn
	}

	static String getGroupRuleHash(AWSSecurityGroup cloudItem) {
		MessageDigest digest = MessageDigest.getInstance("MD5")
		digest.update([name: cloudItem.groupName, id: cloudItem.groupId, rules: getGroupRules(cloudItem)].toString().bytes)
		digest.digest().encodeHex().toString()
	}

	static List getGroupRules(AWSSecurityGroup cloudItem) {
		List rules = []
		['ingress': cloudItem.ipPermissions, 'egress': cloudItem.ipPermissionsEgress].each { String direction, List<IpPermission> permissions ->
			for(IpPermission permission in permissions) {
				def ruleOptions = [
					direction: direction, ipProtocol: permission.ipProtocol, minPort: permission.fromPort, maxPort: permission.toPort
				]
				def ipv4Ranges = permission.ipv4Ranges
				def ipv6Ranges = permission.ipv6Ranges
				def userIdGroupPairs = permission.userIdGroupPairs
				if(ipv4Ranges || ipv6Ranges || userIdGroupPairs) {
					ipv4Ranges?.each { IpRange range ->
						rules << ruleOptions + [description: range.description, ipRange: range.cidrIp]
					}
					ipv6Ranges?.each { Ipv6Range range ->
						rules << ruleOptions + [description: range.description, ipRange: range.cidrIpv6, etherType: "IPv6"]
					}
					userIdGroupPairs?.each { groupPair ->
						rules << ruleOptions + [description: groupPair.description, targetGroupId: groupPair.groupId, targetGroupName: groupPair.groupName]
					}
				} else {
					rules << ruleOptions
				}
			}
		}

		return rules
	}
}
