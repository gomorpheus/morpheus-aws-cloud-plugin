package com.morpheusdata.aws.backup

import com.amazonaws.services.dynamodbv2.model.BackupStatus
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.AmazonEC2Exception
import com.amazonaws.services.ec2.model.DeleteSnapshotRequest
import com.amazonaws.services.ec2.model.DescribeVolumesRequest
import com.amazonaws.services.ec2.model.DescribeVolumesResult
import com.amazonaws.services.ec2.model.Filter
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupExecutionProvider
import com.morpheusdata.core.backup.response.BackupExecutionResponse
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.projection.BackupResultIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.schedulers.Schedulers
import com.morpheusdata.core.util.DateUtility

@Slf4j
class AWSSnapshotExecutionProvider implements BackupExecutionProvider {

	AWSPlugin plugin
	MorpheusContext morpheus

	AWSSnapshotExecutionProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = (AWSPlugin)plugin
		this.morpheus = context
	}


	@Override
	ServiceResponse configureBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse validateBackup(Backup backup, Map config, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse createBackup(Backup backup, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse deleteBackup(Backup backup, Map opts) {
		def rtn = ServiceResponse.success()
		try {
			morpheus.async.backup.backupResult.listIdentityProjections(backup)
				.subscribeOn(Schedulers.io())
				.buffer(50)
				.flatMap {List<BackupResultIdentityProjection> backupResults ->
					morpheus.async.backup.backupResult.listById(backupResults.collect { it.id})
				}
				.doOnNext { BackupResult backupResult ->
					deleteBackupResult(backupResult, opts)
				}.blockingSubscribe()
		} catch (Exception ex) {
			log.error("deleteBackup error: {}", ex, ex)
			rtn.success = false
		}

		return rtn
	}

	@Override
	ServiceResponse deleteBackupResult(BackupResult backupResult, Map opts) {
		def rtn = ServiceResponse.success()
		try {
			def cloudId = backupResult.zoneId ?: backupResult.backup.zoneId
			def snapshotIds = backupResult?.configMap?.snapshots?.collect{ it.snapshotId }
			String regionCode = backupResult?.configMap?.regionCode as String

			def cloud
			if(cloudId) {
				cloud = morpheus.async.cloud.get(cloudId).blockingGet()
			} else {
				def container = morpheus.async.workload.get(backupResult.containerId).blockingGet()
				def server = container?.server
				cloud = server?.cloud
			}

			if(cloud) {
				snapshotIds.each{ snapshotId ->
					deregisterSnapshotImages(snapshotId, cloud, regionCode)
					def result = deleteSnapshot(snapshotId, cloud, regionCode)
					if(!result.success) {
						rtn.success = false
					}
				}
			}
		} catch(Exception ex) {
			log.error("deleteBackupResult error: {}", ex, ex)
		}

		return rtn
	}

	@Override
	ServiceResponse prepareExecuteBackup(Backup backupModel, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse prepareBackupResult(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse<BackupExecutionResponse> executeBackup(Backup backup, BackupResult backupResult, Map executionConfig, Cloud cloud, ComputeServer computeServer, Map opts) {
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))

		try {
			log.debug("backupConfig container: {}", rtn)

			//doing this in new session b/c it may be executing async
			def container
			String regionCode
			regionCode = computeServer.region?.regionCode

			if(computeServer.serverOs?.platform != 'windows') {
				morpheus.executeCommandOnServer(computeServer, 'sync')
			} else {
				if(computeServer.sourceImage && computeServer.sourceImage.isCloudInit) {
					morpheus.executeCommandOnServer(computeServer, '''Remove-Item -Force -Path C:\\ProgramData\\Amazon\\EC2Launch\\state\\.run-once
Write-VolumeCache -DriveLetter C
						''')
				}
			}
			def ec2InstanceId = computeServer.externalId
			//execute snapshot
			def result = createSnapshotsForInstance(ec2InstanceId, cloud, regionCode)
			if(result.success) {
				def totalSize = (result.snapshots?.volumeSize?.sum() ?: 0) * 1024
				def targetArchive = []
				def snapshots = []
				result.snapshots?.each { snapshot ->
					def volumeSize = snapshot.volumeSize ?: 0
					def diskType = snapshot.deviceName?.equals("/dev/sda1") ? "root" : "data"
					targetArchive << snapshot.snapshotId
					snapshots << [
						volumeId:snapshot.volumeId, snapshotId:snapshot.snapshotId, volumeSize:volumeSize, sizeInMb:(volumeSize * 1024),
						deviceName:snapshot.deviceName, diskType:diskType, availabilityZone:snapshot.availabilityZone, volumeType:snapshot.volumeType, iops:snapshot.iops,
						kmsKeyId: computeServer.getConfigProperty('kmsKeyId')
					]
				}
				rtn.data.backupResult.zoneId
				rtn.data.backupResult.status = BackupStatusUtility.IN_PROGRESS
				rtn.data.backupResult.resultArchive = targetArchive.join(",")
				rtn.data.backupResult.sizeInMb = totalSize
				rtn.data.backupResult.setConfigProperty("regionCode", regionCode)
				rtn.data.backupResult.setConfigProperty("snapshots", snapshots)
				rtn.data.updates = true
			} else {
				rtn.data.backupResult.sizeInMb = 0
				rtn.data.backupResult.status = BackupStatusUtility.FAILED
				rtn.data.updates = true
			}
			rtn.success = true
		} catch(e) {
			log.error("executeBackup: ${e}", e)
			rtn.msg = e.getMessage()
			rtn.data.backupResult.errorOutput = "Failed to execute backup".encodeAsBase64()
			rtn.data.backupResult.sizeInMb = 0
			rtn.data.backupResult.status = BackupStatusUtility.FAILED
			rtn.data.updates = true
		}
		return rtn
	}

	@Override
	ServiceResponse<BackupExecutionResponse> refreshBackupResult(BackupResult backupResult) {
		ServiceResponse<BackupExecutionResponse> rtn = ServiceResponse.prepare(new BackupExecutionResponse(backupResult))
		log.debug("syncBackupResult {}", backupResult)

		try {
			def amazonClient
			ComputeServer server = morpheus.async.computeServer.get(backupResult.serverId).blockingGet()
			Cloud cloud = server?.cloud
			if(cloud) {
				amazonClient = plugin.getAmazonClient(cloud, false, server.region?.regionCode)
			}


			def snapshotIds = backupResult?.configMap?.snapshots?.collect{it.snapshotId}
			def completeCount = 0
			Boolean error = false
			if(amazonClient) {
				snapshotIds?.each{ snapshotId ->
					def opts = [snapshotId: snapshotId, amazonClient: amazonClient]
					def snapshotResults = AmazonComputeUtility.getSnapshot(opts)
					if(snapshotResults.success == true && snapshotResults?.snapshot) {
						def snapshot = snapshotResults.snapshot
						def state = snapshot?.state
						if(state == "completed") {
							completeCount++
						} else if(state == 'error') {
							error = true
						}
					}
				}
			} else {
				error = true
			}

			if(!error && completeCount == snapshotIds?.size()) { //all complete, update status
				rtn.data.updates = true
				rtn.data.backupResult.status = BackupStatusUtility.SUCCEEDED
				if(!backupResult.endDate) {
					rtn.data.backupResult.endDate = new Date()
					def startDate = backupResult.startDate
					if(startDate) {
						def start = DateUtility.parseDate(startDate)
						def end = rtn.data.backupResult.endDate
						rtn.data.backupResult.durationMillis = end.time - start.time
					}
				}
				if(server.sourceImage && server.sourceImage.isCloudInit && server.serverOs?.platform == 'windows') {
					morpheus.executeCommandOnServer(server, 'New-Item -ItemType file C:\\ProgramData\\Amazon\\EC2Launch\\state\\.run-once')
				}
				rtn.success = true
			} else if(error) {
				rtn.data.updates = true
				rtn.data.backupResult.status = BackupStatusUtility.FAILED
				if(!backupResult.endDate) {
					rtn.data.backupResult.endDate = new Date()
					def startDate = backupResult.startDate
					if(startDate) {
						def start = DateUtility.parseDate(startDate)
						def end = rtn.data.backupResult.endDate
						rtn.data.backupResult.durationMillis = end.time - start.time
					}
				}
			} else {
				rtn.data.updates = false
				rtn.success = true
			}
		} catch (Exception e) {
			log.error("refreshBackupResult error: {}", e, e)
		}

		return rtn
	}

	@Override
	ServiceResponse cancelBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse extractBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	protected createSnapshotsForInstance(instanceId, Cloud cloud, String regionCode=null){
		log.debug "createSnapshotsForInstance: ${instanceId}"
		def rtn = [success:true]
		rtn.snapshots = []
		def volumes = getVolumesForInstance(instanceId, cloud,regionCode)
		def volumeIds = volumes.collect{it.volumeId}
		volumeIds.each { volumeId ->
			rtn.snapshots << createSnapshot(instanceId, volumeId, cloud, regionCode)
		}
		return rtn
	}

	protected getVolumesForInstance(instanceId, Cloud cloud,String regionCode=null){
		def volumeIds = []
		AmazonEC2 ec2Client = plugin.getAmazonClient(cloud, false, regionCode)
		def describeVolumesRequest = new DescribeVolumesRequest()
		def filter = new Filter().withName("attachment.instance-id").withValues([instanceId])
		describeVolumesRequest.setFilters([filter])
		DescribeVolumesResult describeVolumesResult = ec2Client.describeVolumes(describeVolumesRequest)
		def volumes = describeVolumesResult.getVolumes()
		return volumes
	}

	protected createSnapshot(instanceId, volumeId, Cloud cloud, String regionCode=null) {
		log.debug "createSnapshot: ${instanceId}, ${volumeId}"
		def rtn = [:]
		def awsClient = plugin.getAmazonClient(cloud, false, regionCode)
		def snapShotResult = AmazonComputeUtility.snapshotVolume([volumeId: volumeId, amazonClient: awsClient, instanceId: instanceId])
		rtn.snapshotId = snapShotResult.snapshotId
		rtn.volumeSize = snapShotResult.volumeSize
		rtn.volumeId = snapShotResult.volumeId
		// Need to save off the device name
		def volume = AmazonComputeUtility.getVolumeDetail([volumeId: snapShotResult.volumeId, amazonClient: awsClient])?.volume
		rtn.availabilityZone = volume.getAvailabilityZone()
		rtn.volumeType = volume.getVolumeType()
		rtn.iops = volume.getIops()
		def attachments = volume.getAttachments()
		if(attachments.size() > 0 ) {
			rtn.deviceName = attachments[0].getDevice()
		}

		return rtn
	}

	protected deleteSnapshot(String snapshotId, Cloud cloud,String regionCode=null) {
		def rtn = ServiceResponse.success()
		try{
			AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
			DeleteSnapshotRequest deleteSnapshotRequest  = new DeleteSnapshotRequest(snapshotId)
			amazonClient.deleteSnapshot(deleteSnapshotRequest)
			log.debug("deleted snapshot ${snapshotId}")
		} catch(AmazonEC2Exception exAws) {
			if(exAws.getErrorCode() != 'InvalidSnapshot.NotFound') {
				rtn.success = false
				log.error("Failed to delete snapshot ${snapshotId}", exAws)
			}
		} catch (Exception ex) {
			rtn.success = false
			log.error("Failed to delete snapshot ${snapshotId}", ex)
		}
		return rtn
	}

	protected deregisterSnapshotImages(String snapshotId, Cloud cloud, String regionCode=null) {
		def rtn = [success:true]
		try {
			AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
			def snapshotImageRtn = AmazonComputeUtility.listSnapshotImages([amazonClient: amazonClient, snapshotId: snapshotId])
			if(snapshotImageRtn?.success == true){
				def snapshotImages = snapshotImageRtn?.imageList
				snapshotImages?.each { image ->
					log.debug("deregisterSnapshotImage ${image.imageId}")
					AmazonComputeUtility.deregisterImage([amazonClient: amazonClient, imageId: image.imageId])
				}
			}
		} catch (Exception ex) {
			rtn.success = false
			log.error("Failed to deregister images for snapshot ${snapshotId}")
		}
	}
}
