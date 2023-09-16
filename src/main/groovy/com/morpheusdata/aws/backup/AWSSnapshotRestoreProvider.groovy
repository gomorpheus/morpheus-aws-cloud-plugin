package com.morpheusdata.aws.backup

import com.amazonaws.partitions.model.Service
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DeleteVolumeRequest
import com.amazonaws.services.ec2.model.DescribeInstancesRequest
import com.amazonaws.services.ec2.model.DescribeVolumesRequest
import com.amazonaws.services.ec2.model.DescribeVolumesResult
import com.amazonaws.services.ec2.model.Filter
import com.amazonaws.services.ec2.model.StartInstancesRequest
import com.amazonaws.services.ec2.model.StopInstancesRequest
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.backup.BackupRestoreProvider
import com.morpheusdata.core.backup.response.BackupRestoreResponse
import com.morpheusdata.core.backup.util.BackupResultUtility
import com.morpheusdata.core.backup.util.BackupStatusUtility
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupRestore
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.Workload
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class AWSSnapshotRestoreProvider implements BackupRestoreProvider{

	AWSPlugin plugin
	MorpheusContext morpheus

	AWSSnapshotRestoreProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = (AWSPlugin)plugin
		this.morpheus = context
	}

	@Override
	ServiceResponse configureRestoreBackup(BackupResult backupResultModel, Map config, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse getBackupRestoreInstanceConfig(BackupResult backupResultModel, Instance instanceModel, Map restoreConfig, Map opts) {
		return ServiceResponse.success(restoreConfig)
	}

	@Override
	ServiceResponse validateRestoreBackup(BackupResult backupResultModel, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse getRestoreOptions(Backup backupModel, Map opts) {
		return ServiceResponse.success()
	}

	@Override
	ServiceResponse<BackupRestoreResponse> restoreBackup(BackupRestore backupRestore, BackupResult backupResult, Backup backup, Map opts) {
		ServiceResponse<BackupRestoreResponse> rtn = ServiceResponse.prepare(new BackupRestoreResponse(backupRestore))
		log.debug("restoreBackup {}, opts {}", backupResult, opts)
		try {
			Workload workload = morpheus.async.workload.get(opts.containerId).blockingGet()
			ComputeServer server = workload.server
			Cloud cloud = server.cloud
			def ec2InstanceId = server?.externalId
			def backupResultConfig = backupResult.getConfigMap()
			def snapshotIds = backupResultConfig.snapshots?.collect{ it.snapshotId }
			log.debug("snapshotIds: {}", snapshotIds)
			// Detach existing volumes
			def volumesDetached = stopAndDetachVolumes(ec2InstanceId, cloud, server.region?.regionCode)
			snapshotIds.each { snapshotId ->
				def snapshotConfig = backupResultConfig.snapshots?.find { it.snapshotId == snapshotId }
				if(!snapshotConfig) {
					throw new RuntimeException("Unable to restore backup without snapshot configuration")
				}
				restoreSnapshotToInstance(ec2InstanceId, snapshotConfig, cloud, server?.region?.regionCode)
			}
			def startStatus = startInstance(ec2InstanceId, cloud, server.region?.regionCode)
			// Only delete the volumes after the server started successfully
			if(startStatus == 'running') {
				def amazonClient = plugin.getAmazonClient(cloud, false , server.region?.regionCode)
				volumesDetached?.each { volumeId ->
					log.debug "deleting the existing volume: ${volumeId}"
					AmazonComputeUtility.deleteVolume([amzonClient: amazonClient, volumeId: volumeId])
				}
			}
			updateInstanceIp(server.id, ec2InstanceId, cloud)
			rtn.success = true
			rtn.data.backupRestore.status = BackupStatusUtility.SUCCEEDED
		} catch(e) {
			log.error("restoreBackup: ${e}", e)
			rtn.success = false
			rtn.msg = e.getMessage()
			rtn.data.backupRestore.errorMessage = e.getMessage()
			rtn.data.backupRestore.status = BackupStatusUtility.FAILED
		}

		return rtn
	}

	@Override
	ServiceResponse refreshBackupRestoreResult(BackupRestore backupRestore, BackupResult backupResult) {
		return null
	}

	protected restoreSnapshotToInstance(instanceId, snapshotConfig, Cloud cloud, String regionCode=null) {
		log.debug("restoreSnapshotToInstance: instanceId {}, snapshotConfig {}", instanceId, snapshotConfig)
		def snapshotId = snapshotConfig.snapshotId
		def deviceName = snapshotConfig.deviceName
		def availabilityZone = snapshotConfig.availabilityZone
		def volumeType = snapshotConfig.volumeType
		def iops = snapshotConfig.iops
		AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		//create the new volume from the snapshot
		def addVolumeResult = AmazonComputeUtility.addVolume([availabilityId: availabilityZone, diskType: volumeType, iops: iops, snapshotId: snapshotId, amazonClient: amazonClient, kmsKeyId: snapshotConfig.kmsKeyId])
		def newVolumeId = addVolumeResult.volume?.volumeId
		AmazonComputeUtility.waitForVolumeState([volumeId: newVolumeId, requestedState: 'available', amazonClient: amazonClient])
		//attach the new volume
		AmazonComputeUtility.attachVolume([volumeId: newVolumeId, serverId: instanceId, deviceName: deviceName, amazonClient: amazonClient])
	}

	protected stopAndDetachVolumes(instanceId, Cloud cloud, String regionCode = null) {
		def volumesDetached = []
		log.debug("stopAndDetachVolumes: instanceId {}", instanceId)
		def ec2Client = plugin.getAmazonClient(cloud, false, regionCode)
		stopInstance(instanceId, cloud,regionCode)
		def existingVolumes = getVolumesForInstance(instanceId, cloud,regionCode)
		existingVolumes?.each { existingVolume ->
			def existingVolumeId = existingVolume.volumeId
			log.debug("detaching volume {} from instance {}", existingVolumeId, instanceId)
			AmazonComputeUtility.detachVolume([volumeId:existingVolumeId, instanceId:instanceId, amazonClient:ec2Client])
			volumesDetached << existingVolumeId
		}
		return volumesDetached
	}

	protected getVolumesForInstance(instanceId, Cloud cloud, String regionCode=null){
		def volumeIds = []
		AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		def describeVolumesRequest = new DescribeVolumesRequest()
		def filter = new Filter().withName("attachment.instance-id").withValues([instanceId])
		describeVolumesRequest.setFilters([filter])
		DescribeVolumesResult describeVolumesResult = amazonClient.describeVolumes(describeVolumesRequest)
		def volumes = describeVolumesResult.getVolumes()
		return volumes
	}

	protected stopInstance(instanceId, Cloud cloud, String regionCode = null){
		AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		def stopInstancesRequest = new StopInstancesRequest()
		stopInstancesRequest.setInstanceIds([instanceId])
		def result = amazonClient.stopInstances(stopInstancesRequest)
		waitForInstanceState(instanceId, "stopped", cloud,regionCode)
	}

	protected startInstance(instanceId, Cloud cloud, String regionCode=null){
		AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		def startInstancesRequest = new StartInstancesRequest()
		startInstancesRequest.setInstanceIds([instanceId])
		def result = amazonClient.startInstances(startInstancesRequest)
		waitForInstanceState(instanceId, "running", cloud)
	}

	protected waitForInstanceState(instanceId, requestedState, Cloud cloud, String regionCode=null){
		AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, regionCode)
		log.debug("waiting for instance ${instanceId} to go to state: ${requestedState}")
		def status = ""
		while(!(requestedState).equals(status)){
			sleep(15000)
			def describeInstanceRequest = new DescribeInstancesRequest().withInstanceIds(instanceId)
			def describeInstanceResult = amazonClient.describeInstances(describeInstanceRequest)
			def state = describeInstanceResult.getReservations().get(0).getInstances().get(0).getState()
			status = state.name
		}
		return status
	}

	def updateInstanceIp(Long serverId, String instanceId, Cloud cloud) {
		log.debug("updateInstanceIp: {}, {}", serverId, instanceId)
		try {
			def morphServer = morpheus.async.computeServer.get(serverId).blockingGet()
			AmazonEC2 amazonClient = plugin.getAmazonClient(cloud, false, morphServer.region.regionCode)
			def ec2InstanceResults = AmazonComputeUtility.getServerDetail([amazonClient: amazonClient, externalId: instanceId])
			def ec2Instance = ec2InstanceResults.server
			String publicIp = ec2Instance.getPublicIpAddress()
			log.debug("publicIp is {}", publicIp)
			if(publicIp) {
				def networkInterface = morphServer.interfaces?.find {
					it.ipAddress == morphServer.internalIp || it.publicIpAddress == morphServer.externalIp
				}
				if(networkInterface) {
					log.debug("refreshing instance public ip from: {} to: {}", networkInterface.publicIpAddress, publicIp)
					if(morphServer.sshHost == networkInterface.publicIpAddress || morphServer.sshHost == morphServer.externalIp) {
						morphServer.sshHost = publicIp
					}
					networkInterface.publicIpAddress = publicIp
					morphServer.setExternalIp(publicIp)
					morpheus.async.computeServer.save(morphServer)
				}
			}
		} catch(e)  {
			log.error "Error in updatingInstanceIp: ${e}", e
		}
	}
}
