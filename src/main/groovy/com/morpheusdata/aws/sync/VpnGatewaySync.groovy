package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.VpnGateway
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import com.morpheusdata.model.projection.CloudRegionIdentity
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class VpnGatewaySync extends InternalResourceSync {

	VpnGatewaySync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
				def amazonClient = plugin.getAmazonClient(cloud,false, region.externalId)
				def apiList = AmazonComputeUtility.listVpnGateways([amazonClient: amazonClient],[:])
				if(apiList.success) {
					Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,"aws.cloudFormation.ec2.vpnGateway", region.externalId)
					SyncTask<AccountResourceIdentityProjection, VpnGateway, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.vpnGateways as Collection<VpnGateway>)
					syncTask.addMatchFunction { AccountResourceIdentityProjection existingItem, VpnGateway cloudItem ->
						existingItem.externalId == cloudItem.vpnGatewayId
					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItem<AccountResourceIdentityProjection, VpnGateway>> updateItems ->
						morpheusContext.async.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.onAdd { itemsToAdd ->
						addMissingVpnGateways(itemsToAdd, region)
					}.onUpdate { List<SyncTask.UpdateItem<AccountResource, VpnGateway>> updateItems ->
						updateMatchedVpnGateways(updateItems, region)
					}.onDelete { removeItems ->
						removeMissingResources(removeItems)
					}.start()
				} else {
					log.error("Error Caching VPC Gateways for Region: {} - {}", region.externalId, apiList.msg)
					return Single.just(false).toObservable() //ignore invalid region
				}
			}
		}  catch(Exception ex) {
			log.error("VpnGatewaySync error: {}", ex, ex)
		}
	}

	protected String getCategory() {
		return "amazon.ec2.vpn.gateway.${cloud.id}"
	}

	protected void addMissingVpnGateways(Collection<VpnGateway> addList, CloudRegionIdentity region) {
		def adds = []
		for(VpnGateway cloudItem in addList) {
			def name = cloudItem.tags?.find{it.key == 'Name'}?.value ?: cloudItem.vpnGatewayId
			AccountResource add = new AccountResource(
				owner:cloud.account, category:category, code: category + '.' + cloudItem.vpnGatewayId,
				externalId:cloudItem.vpnGatewayId, cloudId:cloud.id, type: new AccountResourceType(code: 'aws.cloudFormation.ec2.vpnGateway'),
				resourceType:'VPNGateway', cloudName: cloud.name, name: name, displayName: name, region: new CloudRegion(id: region.id)
			)
			add.configMap = [amazonSideAsn: cloudItem.amazonSideAsn, availabilityZone: cloudItem.availabilityZone]
			adds << add
		}
		if(adds) {
			morpheusContext.async.cloud.resource.create(adds).blockingGet()
		}

	}

	protected void updateMatchedVpnGateways(List<SyncTask.UpdateItem<AccountResource, VpnGateway>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name = masterItem.tags?.find{it.key == 'Name'}?.value ?: masterItem.vpnGatewayId

			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}
			if(existingItem.region?.id != region.id) {
				existingItem.region = new CloudRegion(id: region.id)
				save = true
			}
			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.async.cloud.resource.save(updates).blockingGet()
		}
	}

}
