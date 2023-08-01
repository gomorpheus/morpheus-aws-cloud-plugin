package com.morpheusdata.aws

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
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class AWSScaleProvider implements ScaleProvider {
    MorpheusContext morpheusContext
    AWSPlugin plugin

    public static final PROVIDER_CODE = 'plugin.awsscalegroup'

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

    protected getAmazonAutoScaleClient(Cloud cloud, Boolean fresh = false, String region=null) {
        return this.@plugin.getAmazonAutoScaleClient(cloud, fresh, region)
    }
}
