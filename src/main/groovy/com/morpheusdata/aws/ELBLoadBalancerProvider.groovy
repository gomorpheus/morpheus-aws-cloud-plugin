package com.morpheusdata.aws

import com.amazonaws.services.elasticloadbalancingv2.AmazonElasticLoadBalancingClient
import com.amazonaws.services.elasticloadbalancingv2.model.DeleteLoadBalancerRequest
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.network.loadbalancer.LoadBalancerProvider
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.NetworkLoadBalancerInstance
import com.morpheusdata.model.NetworkLoadBalancerMonitor
import com.morpheusdata.model.NetworkLoadBalancerNode
import com.morpheusdata.model.NetworkLoadBalancerPolicy
import com.morpheusdata.model.NetworkLoadBalancerPool
import com.morpheusdata.model.NetworkLoadBalancerProfile
import com.morpheusdata.model.NetworkLoadBalancerRule
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class ELBLoadBalancerProvider implements LoadBalancerProvider {
    MorpheusContext morpheusContext
    Plugin plugin

    private static final PROVIDER_CODE = 'plugin.amazon-elb'

    public ELBLoadBalancerProvider(Plugin plugin, MorpheusContext context) {
        super()
        this.@plugin = plugin
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
    Collection<NetworkLoadBalancerType> getLoadBalancerTypes() {
        return null
    }

    @Override
    ServiceResponse validate(NetworkLoadBalancer loadBalancer, Map opts) {
        return null
    }

    @Override
    ServiceResponse initializeLoadBalancer(NetworkLoadBalancer loadBalancer, Map opts) {
        return null
    }

    @Override
    ServiceResponse refresh(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerProfile(NetworkLoadBalancerProfile profile) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerHealthMonitor(NetworkLoadBalancerMonitor monitor) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerPolicy(NetworkLoadBalancerPolicy loadBalancerPolicy) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerRule(NetworkLoadBalancerRule rule) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerNode(NetworkLoadBalancerNode node) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerPool(NetworkLoadBalancerPool pool) {
        return null
    }

    @Override
    ServiceResponse createLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerVirtualServer(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse addInstance(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse removeInstance(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse updateInstance(NetworkLoadBalancerInstance instance) {
        return null
    }

    @Override
    ServiceResponse addLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse updateLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse deleteLoadBalancer(NetworkLoadBalancer loadBalancer) {
        return null
    }

    @Override
    ServiceResponse setAdditionalConfiguration(NetworkLoadBalancer loadBalancer, Map opts) {
        return null
    }

    @Override
    ServiceResponse validateLoadBalancerInstanceConfiguration(NetworkLoadBalancer loadBalancer, Instance instance) {
        return null
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
        return 'Amazon ELB'
    }
}
