package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Subnet
import com.amazonaws.services.rds.AmazonRDS
import com.amazonaws.services.rds.model.DBSubnetGroup
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Network
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.projection.NetworkIdentityProjection
import com.morpheusdata.model.projection.ReferenceDataSyncProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.ObservableSource
import io.reactivex.Single

@Slf4j
class DbSubnetGroupSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin

	public DbSubnetGroupSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}


	def execute() {
		morpheusContext.cloud.region.listIdentityProjections(cloud.id).flatMap {
			final String regionCode = it.externalId
			AmazonRDS amazonClient = AmazonComputeUtility.getAmazonRdsClient(cloud,false,it.externalId)
			def dbSubnetResults = AmazonComputeUtility.listDbSubnetGroups([amazonClient: amazonClient])
			if(dbSubnetResults.success) {
				Observable<ReferenceDataSyncProjection> domainRecords = morpheusContext.cloud.listReferenceDataByCategory(cloud,"amazon.ec2.db.subnetgroup.${cloud.id}.${regionCode}".toString()).mergeWith(morpheusContext.cloud.listReferenceDataByCategory(cloud,"amazon.ec2.db.subnetgroup.${cloud.id}".toString()))
				SyncTask<ReferenceDataSyncProjection, DBSubnetGroup, ReferenceData> syncTask = new SyncTask<>(domainRecords, dbSubnetResults.subnetGroupList as Collection<DBSubnetGroup>)
				return syncTask.addMatchFunction { ReferenceDataSyncProjection domainObject, DBSubnetGroup data ->
					domainObject.name == data.getDBSubnetGroupName()
				}.onDelete { removeItems ->
					removeMissingDbSubnetGroups(removeItems)
				}.onUpdate { List<SyncTask.UpdateItem<ReferenceData, DBSubnetGroup>> updateItems ->
					//nothing to update for now(probably should at some point)
				}.onAdd { itemsToAdd ->
					addMissingDbSubnetGroups(itemsToAdd, regionCode)
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<ReferenceDataSyncProjection, DBSubnetGroup>> updateItems ->
					morpheusContext.cloud.listReferenceDataById(updateItems.collect { it.existingItem.id } as List<Long>)
				}.observe()
			} else {
				log.error("Error Caching DB Subnet Groups for Region: {} - {}",regionCode,dbSubnetResults.msg)
				return Single.just(false).toObservable() //ignore invalid region
			}
		}.blockingSubscribe()
	}

	private String getCategory(String regionCode) {
		return "amazon.ec2.db.subnetgroup.${cloud.id}.${regionCode}"
	}


	def addMissingDbSubnetGroups(Collection addList, String regionCode) {
		log.debug "addMissingCategories ${cloud} ${addList.size()}"
		def adds = []

		for(DBSubnetGroup data in addList) {
			def add = new ReferenceData(account:cloud.owner, code:"amazon.ec2.db.subnetgroup.${cloud.id}.${data.getDBSubnetGroupName()}", category:getCategory(regionCode),
					name:data.getDBSubnetGroupName(), keyValue:data.getDBSubnetGroupName(), value:data.getDBSubnetGroupName(),
					xRef:data.getVpcId(), refType:'ComputeZone', refId:"${cloud.id}")
			adds << add
		}

		if(adds) {
			morpheusContext.cloud.create(adds, cloud, getCategory(regionCode)).blockingGet()
		}
	}

	private removeMissingDbSubnetGroups(List<ReferenceDataSyncProjection> removeList) {
		morpheusContext.cloud.remove(removeList).blockingGet()
	}

}
