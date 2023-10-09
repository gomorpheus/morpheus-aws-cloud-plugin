package com.morpheusdata.aws.sync

import com.amazonaws.services.cloudwatch.model.MetricAlarm
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.OperationNotification
import com.morpheusdata.model.projection.OperationNotificationIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import io.reactivex.Single

/**
 * Sync class for syncing VPCs within an AWS Cloud account
 * This sync system first iterates over a list of regions to sync for using the region list
 */
@Slf4j
class AlarmSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin


	public AlarmSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).concatMap {
				final String regionCode = it.externalId
				String regionScopedCategory = "amazon.alarm.${cloud.id}.${regionCode}"
				String cloudCategory = "amazon.alarm.${cloud.id}"
				def amazonClient = AmazonComputeUtility.getAmazonCloudWatchClient(cloud,false,it.externalId)
				def alarmResults = AmazonComputeUtility.listCloudWatchAlarms([amazonClient: amazonClient])
				if(alarmResults.success) {
					Observable<OperationNotificationIdentityProjection> domainRecords = morpheusContext.async.operationNotification.listIdentityProjections(regionScopedCategory).mergeWith(morpheusContext.async.operationNotification.listIdentityProjections(cloudCategory))
					SyncTask<OperationNotificationIdentityProjection, MetricAlarm, OperationNotification> syncTask = new SyncTask<>(domainRecords, alarmResults.alarms as Collection<MetricAlarm>)
					return syncTask.addMatchFunction { OperationNotificationIdentityProjection domainObject, MetricAlarm data ->
						domainObject.externalId == data.getAlarmArn()
					}.onDelete { removeItems ->
						removeMissingAlarms(removeItems)
					}.onUpdate { List<SyncTask.UpdateItem<OperationNotification, MetricAlarm>> updateItems ->
						updateMatchedAlarms(updateItems,regionCode)
					}.onAdd { itemsToAdd ->
						addMissingAlarms(itemsToAdd, regionCode)
					}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<OperationNotificationIdentityProjection, MetricAlarm>> updateItems ->
						return morpheusContext.async.operationNotification.listById(updateItems.collect { it.existingItem.id } as List<Long>)
					}.observe()
				} else {
					log.error("Error Caching VPCs for Region: {} - {}",regionCode,alarmResults.msg)
					return Single.just(false).toObservable() //ignore invalid region
				}
			}.blockingSubscribe()
		} catch(Exception ex) {
			log.error("AlarmSync error: {}", ex, ex)
		}
	}

	protected void addMissingAlarms(Collection<MetricAlarm> addList, String region) {
		def adds = []
		String zoneCategory = "amazon.alarm.${cloud.id}.${region}"
		def instanceIds = addList.findAll{it.dimensions?.find{it.name == 'InstanceId'}}.collect{it.dimensions?.findAll{d -> d.name == 'InstanceId'}.collect{d -> d.value}}.flatten()
		Map<String, ComputeServer> associatedResources = [:]
		if(instanceIds) {
			associatedResources = morpheusContext.async.computeServer.listByCloudAndExternalIdIn(cloud.id,instanceIds).toList().blockingGet().collectEntries{[(it.externalId):it]}
		}

		for(MetricAlarm cloudItem in addList) {
			def alarmConfig = [account:cloud.owner, category:zoneCategory, name:cloudItem.alarmName,
			   eventKey:cloudItem.metricName, externalId:cloudItem.getAlarmArn(), acknowledged:cloudItem.stateValue?.toUpperCase() == 'OK',
			   acknowledgedDate:null, acknowledgedByUser:null,
			   status:translateAlarmStatus(cloudItem.stateValue), statusMessage:cloudItem.stateReason, startDate:cloudItem.getStateUpdatedTimestamp(),
			   resourceName:cloud.name, refType:'computeZone', refId: cloud.id,
			   uniqueId:cloudItem.getAlarmArn(), regionCode: region]
			def refMatch = findManagedAlarmObject(cloudItem,associatedResources)
			if(refMatch && refMatch.refType && refMatch.refId) {
				alarmConfig.refType = refMatch.refType
				alarmConfig.refId = refMatch.refId
			}
			def add = new OperationNotification(alarmConfig)
			adds << add
		}
		if(adds) {
			morpheusContext.async.operationNotification.create(adds).blockingGet()
		}
	}

	protected void updateMatchedAlarms(List<SyncTask.UpdateItem<OperationNotification, MetricAlarm>> updateList, String region) {
		def updates = []
		def instanceIds = updateList.findAll{it.masterItem.dimensions?.find{it.name == 'InstanceId'} && it.existingItem.refType == null}.collect{it.masterItem.dimensions?.findAll{d -> d.name == 'InstanceId'}.collect{d -> d.value}}.flatten()
		Map<String, ComputeServer> associatedResources = [:]
		if(instanceIds) {
			associatedResources = morpheusContext.async.computeServer.listByCloudAndExternalIdIn(cloud.id,instanceIds).toList().blockingGet().collectEntries{[(it.externalId):it]}
		}
		for(update in updateList) {
			def masterItem = update.masterItem
			def existingItem = update.existingItem
			Boolean save = false

			Boolean acknowledge = update.masterItem.stateValue?.toUpperCase() == 'OK'
			if(existingItem.acknowledged != true && existingItem.acknowledged != acknowledge) {
				existingItem.acknowledged = acknowledge
				save = true
			}

			if(existingItem.refType == null) {
				def refMatch = findManagedAlarmObject(update.masterItem,associatedResources)
				if(refMatch && refMatch.refType && refMatch.refId) {
					existingItem.refType = refMatch.refType
					existingItem.refId = refMatch.refId
					save = true
				}
			}

			if(region && existingItem.regionCode != region) {
				existingItem.regionCode = region
				save = true
			}
			def newStatus = translateAlarmStatus(update.masterItem.stateValue)
			if(existingItem.status != newStatus) {
				existingItem.status = newStatus
				save = true
			}
			if(save) {
				updates << existingItem
			}
		}
		if(updates) {
			morpheusContext.async.operationNotification.save(updates).blockingGet()
		}
	}

	protected removeMissingAlarms(List<OperationNotificationIdentityProjection> removeList) {
		morpheusContext.async.operationNotification.remove(removeList).blockingGet()
	}

	static String translateAlarmStatus(String state) {
		String rtn
		if(state.toUpperCase() == 'INSUFFICIENT_DATA')
			rtn = 'warning'
		else if(state.toUpperCase() == 'ALARM')
			rtn = 'error'
		else if(state.toUpperCase() == 'OK')
			rtn = 'ok'
		else
			rtn = 'unknown'
		return rtn
	}

	def findManagedAlarmObject(MetricAlarm cloudItem,Map<String, ComputeServer> associatedResources) {
		//find the matching item in our db

		def rtn = [refType:'computeZone', refId:cloud.id]
		def instanceDimension = cloudItem.dimensions?.find{it.name == 'InstanceId'}
		if(instanceDimension) {
			def match = associatedResources[instanceDimension.value]
			if(match) {
				rtn.refType = 'computeServer'
				rtn.refId = match.id
			}
		}
		return rtn
	}
}