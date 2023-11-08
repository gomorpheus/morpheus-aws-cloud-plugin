package com.morpheusdata.aws

import com.amazonaws.services.autoscaling.model.ScalingPolicy
import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.ScaleProvider
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.InstanceScale
import com.morpheusdata.model.InstanceScaleType
import com.morpheusdata.model.InstanceThreshold
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class AWSScaleProvider implements ScaleProvider {
    MorpheusContext morpheusContext
    AWSPlugin plugin

    public static final PROVIDER_CODE = 'awsscalegroup'

    public AWSScaleProvider(Plugin plugin, MorpheusContext context) {
        super()
        this.@plugin = (AWSPlugin)plugin
        this.@morpheusContext = context
    }

    @Override
    String getDescription() {
        return null
    }

    @Override
    Icon getIcon() {
        return null
    }

    @Override
    Collection<OptionType> getOptionTypes() {
        return null
    }

    @Override
    Collection<InstanceScaleType> getScaleTypes() {
        InstanceScaleType type = new InstanceScaleType(
            code:PROVIDER_CODE,
            name:'Amazon Auto Scale Group',
            internalControl:false,
            displayOrder:3,
            scaleService:'pluginScaleService',
            plugin:true,
            embedded:false
        )
        return [type]
    }

    @Override
    MorpheusContext getMorpheus() {
        return this.@morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return this.@plugin
    }

    @Override
    String getCode() {
        return PROVIDER_CODE
    }

    @Override
    String getName() {
        return 'Amazon Scale Group'
    }

    @Override
    ServiceResponse postProvisionInstance(Instance instance) {
        log.debug("postProvisionInstance: {}", instance)
        try {
            // Just pass along to update which will create the scale group if needed
            return updateInstance(instance)
        }
        catch(Throwable t) {
            return ServiceResponse.error("Error in creating scale group from Amazon: ${t.message}")
        }
    }

    @Override
    ServiceResponse removeInstance(Instance instance) {
        log.debug("removeInstance: {}", instance)
        ServiceResponse rtn = ServiceResponse.success()

        try {
            // Removes the scale group, policies, and alarms but does NOT remove the instances
            def groupName = instance.scale?.externalId
            if(groupName) {
                Cloud cloud = instance.containers[0].server.cloud
                String regionCode = instance.containers?.getAt(0)?.server?.region?.regionCode

                def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)

                // First... set the min size down to zero to allow ALL instances to be detached
                def updateResults = AmazonComputeUtility.updateAutoScaleGroup(amazonClient, groupName, 0)
                if (!updateResults.success) {
                    rtn = ServiceResponse.error("Error in updating auto scale properties: ${updateResults.error}")
                    log.error rtn.msg
                    return rtn
                }

                // Second... detach all the instances
                AmazonComputeUtility.detachAllInstancesFromScaleGroup(amazonClient, groupName)

                // Third... delete the scale group (deletes policies and alarms)
                def deleteResult = AmazonComputeUtility.deleteAutoScaleGroup(amazonClient, groupName)
                if(deleteResult.success) {
                    // Remove the launch configuration too
                    AmazonComputeUtility.deleteLaunchConfiguration(amazonClient, groupName)
                }
            }

        }
        catch(Throwable t) {
            return ServiceResponse.error("Error in deleting scale group from Amazon: ${t.message}")
        }

        return rtn
    }

    @Override
    ServiceResponse updateInstance(Instance instance) {
        log.debug("updateInstance: {}", instance)
        ServiceResponse rtn = ServiceResponse.success()

        try {
            if (instance.scale) {
                ServiceResponse ensureResults = ensureScaleGroupExists(instance)
                if (!ensureResults.success) {
                    return ensureResults
                }
                def groupName = instance.scale.externalId
                Cloud cloud = instance.containers[0].server.cloud
                InstanceThreshold threshold = morpheusContext.async.instance.threshold.get(instance.scale?.threshold?.id).blockingGet()

                if (threshold?.type == AWSScaleProvider.PROVIDER_CODE && threshold.zoneId) {
                    String regionCode = instance.containers?.getAt(0)?.server?.region?.regionCode

                    def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)

                    // Make sure the servers we know about are attached to the group
                    def desiredInstanceIds = instance.containers.collect { Workload c ->
                        c.server.externalId
                    }
                    def attachResults = AmazonComputeUtility.updateInstancesAndPropertiesForScaleGroup(amazonClient, groupName, desiredInstanceIds, threshold.minCount, threshold.maxCount)
                    if(!attachResults.success) {
                        rtn = ServiceResponse.error(attachResults.msg ?: "Error in attaching or detachings instances to scale group")
                        log.error rtn.msg
                        return rtn
                    }

                    def upscaleEnabled = threshold.autoUp && threshold.cpuEnabled
                    def downscaleEnabled = threshold.autoDown && threshold.cpuEnabled
                    def policies = AmazonComputeUtility.getAutoScaleGroupPolicies(amazonClient, groupName)?.policies

                    if (upscaleEnabled)
                        updateOrCreateScalingPolicy(instance, cloud, groupName, 'cpu', threshold.maxCpu, 'up')
                    else
                        removeScalingPolicy(instance, cloud, groupName, policies, 'cpu', 'up')

                    if (downscaleEnabled)
                        updateOrCreateScalingPolicy(instance, cloud, groupName, 'cpu', threshold.minCpu, 'down')
                    else
                        removeScalingPolicy(instance, cloud, groupName, policies, 'cpu', 'sown')
                }
                else {
                    //what to do
                }
            }
        } catch(e) {
            log.error "error in updateInstance: ${e.message}", e
            rtn = ServiceResponse.error(e.message ?: "Error update instance: $e.message}")
        }

        return rtn
    }

    ServiceResponse ensureScaleGroupExists(Instance instance) {
        log.debug("ensureScaleGroupExists: {}", instance)

        // Perform the logic to create the scale group if it does not exist
        ServiceResponse rtn = ServiceResponse.success()
        try {
            Cloud cloud = instance.containers[0].server.cloud
            String regionCode = instance.containers[0].server?.region?.regionCode
            def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)
            def scaleGroupExists = instance.scale.externalId != null && instance.scale.externalId != ''
            if(scaleGroupExists) {
                scaleGroupExists = AmazonComputeUtility.getAutoScaleGroup(amazonClient, instance.scale.externalId).success
            }

            if(!scaleGroupExists) {
                def groupName = getUniqueGroupName(cloud, instance.name, regionCode)
                def createResults = AmazonComputeUtility.createAutoScaleGroup(amazonClient, groupName, instance.containers.size(), instance.containers[0].server.externalId)
                if(createResults.success) {
                    instance.scale.externalId = groupName
                    morpheusContext.async.instance.scale.save([instance.scale]).blockingGet()
                } else {
                    rtn = ServiceResponse.error(createResults.msg ?: "Error in ensuring scale group exists")
                }
            }
        } catch(Throwable t) {
            rtn = ServiceResponse.error("Error in ensuring scale group exists: ${t.message}")
            log.error(rtn.msg, t)
        }
        rtn
    }

    ServiceResponse registerTargetWithScaleGroup(Instance instance, InstanceScale scale, TargetGroup targetGroup) {
        log.debug "registerTargetWithScaleGroup: $scale, $targetGroup"

        ServiceResponse rtn = ServiceResponse.success()
        try {
            def instanceService = morpheus.instance
            Cloud cloud
            instanceService.getInstanceClouds(instance).blockingSubscribe() {
                cloud = it
            }
            String regionCode = instance.containers?.getAt(0)?.server?.region?.regionCode

            def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)
            def createResults = AmazonComputeUtility.registerTargetWithScaleGroup(amazonClient, scale.externalId, targetGroup)
            if(!createResults.success) {
                rtn = ServiceResponse.error(createResults.msg ?: "Error in registering target group with scale group")
            }
        } catch(e) {
            rtn = ServiceResponse.error("Error in registering target group with scale group: ${e.message}")
            log.error rtn.msg, e
        }
        rtn
    }

    protected updateOrCreateScalingPolicy(instance, cloud, groupName, type, threshold, scaleDirection) {
        log.debug("updateOrCreateScalingPolicy: {}")
        def policyName
        switch(type) {
            case 'cpu':
                policyName = scaleDirection == 'up' ? 'morpheus-cpu-increase' : 'morpheus-cpu-decrease'
                break
            default:
                throw new Exception("Unknown type: ${type}")
        }

        // Update the scaling policy
        String regionCode = instance.containers?.getAt(0)?.server?.region?.regionCode

        def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)
        def results = AmazonComputeUtility.createOrUpdateScalingPolicy(amazonClient, groupName, policyName)
        if(!results.success) {
            throw new Exception("Error in updating policy: ${results.msg}")
        }

        def alarmName = "${policyName}-${instance.id}"
        def policyARN = results.policyARN
        amazonClient = AmazonComputeUtility.getAmazonCloudWatchClient(cloud, false, regionCode)
        results = AmazonComputeUtility.createOrUpdateAlarm(amazonClient, type, groupName, alarmName, threshold, scaleDirection == 'up' ? com.amazonaws.services.cloudwatch.model.ComparisonOperator.GreaterThanOrEqualToThreshold : com.amazonaws.services.cloudwatch.model.ComparisonOperator.LessThanThreshold, policyARN)
        if(!results.success) {
            throw new Exception("Error in updating alarm: ${results.msg}")
        }
    }

    private removeScalingPolicy(instance, cloud, groupName, policies, type, scaleDirection) {
        log.debug("removeScalingPolicy: $groupName, $type, $scaleDirection")
        def policyName
        switch(type) {
            case 'cpu':
                policyName = scaleDirection == 'up' ? 'morpheus-cpu-increase' : 'morpheus-cpu-decrease'
                break
            default:
                throw new Exception("Unknown type: ${type}")
        }

        // Determine if this policy (and alarm) exists
        def hasPolicy = false
        policies?.each { ScalingPolicy policy ->
            if(!hasPolicy && policy.policyName == policyName) {
                hasPolicy = true
            }
        }

        if(hasPolicy) {
            // Remove the policy and the alarm
            String regionCode = instance.containers?.getAt(0)?.server?.region?.regionCode

            def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)
            AmazonComputeUtility.removeScalingPolicy(amazonClient, groupName, policyName)

            def alarmName = "${policyName}-${instance.id}"
            amazonClient = AmazonComputeUtility.getAmazonCloudWatchClient(cloud, false, regionCode)
            AmazonComputeUtility.removeAlarm(amazonClient, alarmName)
        }
    }

    protected getUniqueGroupName(Cloud cloud, proposedName, String regionCode=null) {
        // Must make sure it is unique within AWS
        def amazonClient = getAmazonAutoScaleClient(cloud, false, regionCode)
        def currentGroupName = proposedName

        // Must contain only letters, numbers, dashes and start with an alpha character
        currentGroupName = currentGroupName.replaceAll(/[^a-zA-Z0-9\-]/, '')
        def startsWithAlpha = (currentGroupName ==~ /^[(a-z)|(A-Z)].*/)
        if(!startsWithAlpha) {
            currentGroupName = "morpheus${currentGroupName}"
        }
        currentGroupName = currentGroupName.take(255)

        def hasGroupName = true
        def i = 0
        while(hasGroupName == true && i < 100) {
            hasGroupName = AmazonComputeUtility.getAutoScaleGroup(amazonClient, currentGroupName).success
            if(!hasGroupName) {
                // Make sure we don't already have a launch configuration with the group name
                def hasLaunchConfigurationName = AmazonComputeUtility.getLaunchConfiguration(amazonClient, currentGroupName).success
                if(hasLaunchConfigurationName) {
                    hasGroupName = true
                }
            }

            if(hasGroupName) {
                currentGroupName = "${proposedName}-${i}"
                i++
            }
        }
        currentGroupName
    }

    protected getAmazonAutoScaleClient(Cloud cloud, Boolean fresh = false, String region=null) {
        return this.@plugin.getAmazonAutoScaleClient(cloud, fresh, region)
    }
}
