package com.morpheusdata.aws

import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractOptionSourceProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin

class LoadBalancerOptionSourceProvider extends AbstractOptionSourceProvider {
    AWSPlugin plugin
    MorpheusContext morpheusContext

    public LoadBalancerOptionSourceProvider(AWSPlugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
        this.@morpheusContext = context
    }

    @Override
    List<String> getMethodNames() {
        return ['awsPluginLoadBalancerSchemes']
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
        return 'aws-lb-option-source'
    }

    @Override
    String getName() {
        return 'AWS Load Balancer Option Source'
    }

    // option source methods
    def awsPluginLoadBalancerSchemes(params) {
        return [
            [value: AmazonComputeUtility.LOAD_BALANCER_SCHEME_INTERNAL, name: 'Internal'],
            [value: AmazonComputeUtility.LOAD_BALANCER_SCHEME_INTERNET_FACING, name: 'Internet-facing']
        ]
    }
}
