package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.TransitGatewayVpcAttachment
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudRegion
import com.morpheusdata.model.projection.AccountResourceIdentityProjection
import com.morpheusdata.model.projection.CloudRegionIdentity
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

@Slf4j
class TransitGatewayVpcAttachmentSync extends InternalResourceSync {
	public TransitGatewayVpcAttachmentSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).flatMap {CloudRegionIdentity cloudRegion ->
			final String regionCode = cloudRegion.externalId
			def amazonClient = plugin.getAmazonClient(cloud,false,cloudRegion.externalId)
			def apiList = AmazonComputeUtility.listTransitGatewayVpcAttachments([amazonClient: amazonClient],[:])
			if(apiList.success) {
				Observable<AccountResourceIdentityProjection> domainRecords = morpheusContext.async.cloud.resource.listIdentityProjections(cloud.id,'aws.cloudFormation.ec2.transitGatewayAttachment',regionCode)
				SyncTask<AccountResourceIdentityProjection, TransitGatewayVpcAttachment, AccountResource> syncTask = new SyncTask<>(domainRecords, apiList.transitGatewayVpcAttachments as Collection<TransitGatewayVpcAttachment>)
				return syncTask.addMatchFunction { AccountResourceIdentityProjection domainObject, TransitGatewayVpcAttachment data ->
					domainObject.externalId == data.transitGatewayAttachmentId
				}.onDelete { removeItems ->
					removeMissingResources(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<AccountResource, TransitGatewayVpcAttachment>> updateItems ->
					updateMatchedTransitGatewayVpcAttachments(updateItems,cloudRegion)
				}.onAdd { itemsToAdd ->
					addMissingTransitGatewayVpcAttachment(itemsToAdd, cloudRegion)

				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<AccountResourceIdentityProjection, TransitGatewayVpcAttachment>> updateItems ->
					return morpheusContext.async.cloud.resource.listById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching Transit Gateways for Region: {} - {}",regionCode,apiList.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	protected String getCategory() {
		return "amazon.ec2.transit.gateway.attachments.${cloud.id}"
	}

	protected void addMissingTransitGatewayVpcAttachment(Collection<TransitGatewayVpcAttachment> addList, CloudRegionIdentity region) {
		def adds = []

		for(TransitGatewayVpcAttachment cloudItem in addList) {
			def name = cloudItem.tags?.find { it.key == 'Name' }?.value ?: cloudItem.transitGatewayAttachmentId
			adds << new AccountResource(
				owner: cloud.account, category:getCategory(), code:(getCategory() + '.' + cloudItem.transitGatewayAttachmentId),
				externalId:cloudItem.transitGatewayAttachmentId, cloudId:cloud.id, type:new AccountResourceType(code: 'aws.cloudFormation.ec2.transitGatewayAttachment'), resourceType:'TransitGatewayAttachment',
				cloudName: cloud.name, name: name, displayName: name, region: new CloudRegion(id: region.id),
				rawData: JsonOutput.toJson([vpcId: cloudItem.vpcId, state: cloudItem.state])
			)
		}
		if(adds) {
			morpheusContext.async.cloud.resource.create(adds).blockingGet()
		}
	}

	protected void updateMatchedTransitGatewayVpcAttachments(List<SyncTask.UpdateItem<AccountResource, TransitGatewayVpcAttachment>> updateList, CloudRegionIdentity region) {
		def updates = []
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false
			def name
			def nameTag = masterItem.getTags()?.find{it.getKey() == 'Name'}
			name = nameTag?.value ?: masterItem.transitGatewayAttachmentId
			if(existingItem.name != name) {
				existingItem.name = name
				save = true
			}
			String rawData = JsonOutput.toJson([vpcId: masterItem.getVpcId(), state: masterItem.getState()])
			if(existingItem.rawData != rawData) {
				existingItem.rawData = rawData
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
