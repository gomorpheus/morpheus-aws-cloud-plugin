package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Image
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ImageType
import com.morpheusdata.model.MetadataTag
import com.morpheusdata.model.OsType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.VirtualImageLocation
import com.morpheusdata.model.projection.VirtualImageIdentityProjection
import com.morpheusdata.model.projection.VirtualImageLocationIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class ImageSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, VirtualImageIdentityProjection> virtualImages
	private Map<String, OsType> osTypes

	ImageSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).blockingSubscribe { region ->
			def amazonClient = plugin.getAmazonClient(cloud, false, region.externalId)
			def cloudItems = AmazonComputeUtility.listImages([amazonClient: amazonClient, zone: cloud]).imageList
			Observable<VirtualImageIdentityProjection> existingRecords = morpheusContext.async.virtualImage.location.listIdentityProjections(cloud.id, region.externalId)
			SyncTask<VirtualImageIdentityProjection, Image, VirtualImageLocation> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { VirtualImageLocationIdentityProjection existingItem, Image cloudItem ->
				existingItem.externalId == cloudItem.imageId
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<VirtualImageLocationIdentityProjection, VirtualImageLocation>> updateItems ->
				morpheusContext.async.virtualImage.location.listById(updateItems.collect { it.existingItem.id } as List<Long>)
			}.onAdd { itemsToAdd ->
				addMissingVirtualImages(itemsToAdd, region.externalId)
			}.onUpdate { List<SyncTask.UpdateItem<VirtualImageLocation, Image>> updateItems ->
				updateMatchedVirtualImages(updateItems, region.externalId)
			}.onDelete { removeItems ->
				removeMissingVirtualImages(removeItems)
			}.start()
		}
	}

	private addMissingVirtualImages(Collection<Image> addList, String regionCode) {
		// check for images w/o location, i.e. system and user defined
		log.debug "addMissingVirtualImages: ${cloud} ${regionCode} ${addList.size()}"
		def adds = []
		def imageMap = [:]
		for(Image cloudItem in addList) {
			def name = cloudItem.name ?: cloudItem.imageId
			def existingImage = allVirtualImages[name]

			if(existingImage) {
				// only need to add location to existing image
				imageMap[existingImage.externalId] = existingImage
			} else {
				def blockDeviceMap = cloudItem.blockDeviceMappings
				def blockDeviceConfig = []
				blockDeviceMap.each { blockDevice ->
					blockDeviceConfig << [
						deviceName : blockDevice.getDeviceName(), ebs: blockDevice.getEbs(), noDevice: blockDevice.getNoDevice(),
						virtualName: blockDevice.getVirtualName()
					]
				}
				def productCodeConfig = cloudItem.productCodes?.collect { [id: it.productCodeId, type: it.productCodeType] } ?: []
				def imageConfig = [
					category          : "amazon.ec2.image.${cloud.id}", imageRegion: regionCode, name: cloudItem.name ?: cloudItem.imageId,
					code              : "amazon.ec2.image.${cloud.id}.${cloudItem.imageId}", imageType: 'ami', externalType: cloudItem.imageType,
					kernelId          : cloudItem.kernelId, architecture: cloudItem.architecture, description: cloudItem.description, minDisk: 10,
					minRam            : 512l * ComputeUtility.ONE_MEGABYTE, remotePath: cloudItem.imageLocation, hypervisor: cloudItem.hypervisor,
					platform          : (cloudItem.platform == 'windows' ? 'windows' : 'linux'),
					productCode		  : productCodeConfig.encodeAsJSON().toString(), externalId: cloudItem.imageId,
					ramdiskId         : cloudItem.ramdiskId, isCloudInit: (cloudItem.platform == 'windows' ? false : true),
					rootDeviceName    : cloudItem.rootDeviceName, rootDeviceType: cloudItem.rootDeviceType, enhancedNetwork: cloudItem.sriovNetSupport,
					status            : cloudItem.state == 'available' ? 'Active' : cloudItem.state, statusReason: cloudItem.stateReason,
					virtualizationType: cloudItem.virtualizationType, isPublic: cloudItem.public, refType: 'ComputeZone', refId: "${cloud.id}",
					owner             : cloud.owner, account: cloud.owner
				]

				imageConfig.externalDiskId = blockDeviceMap.find { mapping -> mapping.deviceName == cloudItem.rootDeviceName }?.ebs?.snapshotId
				if (imageConfig.platform == 'windows')
					imageConfig.osType = allOsTypes['windows']
				else
					imageConfig.osType = allOsTypes['linux']

				def add = new VirtualImage(imageConfig)
				add.blockDeviceConfig = blockDeviceConfig.encodeAsJSON().toString()
				add.productCode = productCodeConfig.encodeAsJSON().toString()

				if((cloudItem.tags = [[key:'foo', value:'bar']]))
					add.metadata = cloudItem.tags.collect { new MetadataTag(name: it.key, value: it.value) }

				adds << add
			}
		}

		// Create em all!
		log.debug "About to create ${adds.size()} virtualImages"
		morpheusContext.async.virtualImage.create(adds, cloud).blockingGet()

		// Fetch the images that we just created
		def addExternalIds = adds.collect { it.externalId }
		morpheusContext.async.virtualImage.listIdentityProjections(cloud.account.id, [ImageType.ami] as ImageType[]).blockingSubscribe {
			if(addExternalIds.contains(it.externalId)) {
				imageMap[it.externalId] = it
			}
		}

		// Now add the locations
		def locationAdds = []
		addList.each { cloudItem ->
			log.debug "Adding location for ${cloudItem.imageId}"
			VirtualImageIdentityProjection virtualImageLocationProj = imageMap[cloudItem.imageId]
			if(virtualImageLocationProj) {
				def location = new VirtualImageLocation(buildLocationConfig(virtualImageLocationProj))
				location.externalDiskId = virtualImageLocationProj.externalDiskId
				locationAdds << location
			} else {
				//log.warn "Unable to find virtualImage for ${cloudItem.imageId}"
			}
		}

		if(locationAdds) {
			log.debug "About to create ${locationAdds.size()} locations"
			morpheusContext.async.virtualImage.location.create(locationAdds, cloud).blockingGet()
		}
	}

	private updateMatchedVirtualImages(List<SyncTask.UpdateItem<VirtualImageLocation, Image>> updateList, String regionCode) {
		log.debug "updateMatchedVirtualImages: ${cloud} ${regionCode} ${updateList.size()}"
		def saveLocationList = []
		def saveImageList = []
		def virtualImagesById = morpheusContext.async.virtualImage.listById(updateList.collect { it.existingItem.virtualImage.id }).toMap {it.id}.blockingGet()

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def virtualImage = virtualImagesById[existingItem.virtualImage.id]
			def cloudItem = updateItem.masterItem
			def save = false
			def saveImage = false
			def state = cloudItem.state == 'available' ? 'Active' : cloudItem.state
			def externalDiskId = cloudItem.blockDeviceMappings.find { mapping -> mapping.deviceName == cloudItem.rootDeviceName }?.ebs?.snapshotId

			if(existingItem.imageRegion != regionCode) {
				existingItem.imageRegion = regionCode
				save = true
			}
			def imageName = cloudItem.name ?: cloudItem.imageId
			if(existingItem.imageName != imageName) {
				existingItem.imageName = imageName

				if(virtualImage.imageLocations?.size() < 2) {
					virtualImage.name = imageName
					saveImage = true
				}
				save = true
			}
			if(existingItem.externalId != cloudItem.imageId) {
				existingItem.externalId = cloudItem.imageId
				save = true
			}
			if(virtualImage.status != state) {
				virtualImage.status = state
				saveImageList << virtualImage
			}
			if(existingItem.externalDiskId != externalDiskId) {
				existingItem.externalDiskId  = externalDiskId
				save = true
			}
			if(save) {
				saveLocationList << existingItem
			}

			def masterTags = cloudItem.tags?.sort { it.key }?.collect { [name: it.key, value: it.value] } ?: []
			if(virtualImage.metadata.sort { it.name }.collect {[name: it.name, value: it.value] }.encodeAsJSON().toString() != masterTags.encodeAsJSON().toString()) {
				virtualImage.metadata = masterTags.collect {
					new MetadataTag(name: it.name, value: it.value)
				}
				saveImage = true
			}

			if(saveImage) {
				saveImageList << virtualImage
			}
		}

		if(saveLocationList) {
			morpheusContext.async.virtualImage.location.save(saveLocationList, cloud).blockingGet()
		}
		if(saveImageList) {
			morpheusContext.async.virtualImage.save(saveImageList.unique(), cloud).blockingGet()
		}
	}

	private removeMissingVirtualImages(Collection<VirtualImageLocationIdentityProjection> removeList) {
		log.debug "removeMissingVirtualImages: ${cloud} ${removeList.size()}"
		morpheusContext.async.virtualImage.location.remove(removeList).blockingGet()
	}

	private buildLocationConfig(VirtualImageIdentityProjection virtualImage) {
		[
			virtualImage	: new VirtualImage(id: virtualImage.id),
			code        	: "amazon.ec2.image.${cloud.id}.${virtualImage.externalId}",
			internalId  	: virtualImage.externalId, // internalId and externalId match
			externalId  	: virtualImage.externalId,
			imageName   	: virtualImage.name,
			imageRegion 	: cloud.regionCode
		]
	}

	private Map<String, VirtualImageIdentityProjection> getAllVirtualImages() {
		virtualImages ?: (virtualImages = morpheusContext.async.virtualImage.listIdentityProjections(cloud.account.id, [ImageType.ami] as ImageType[]).toMap { it.name }.blockingGet())
	}

	private Map<String, OsType> getAllOsTypes() {
		osTypes ?: (osTypes = morpheusContext.async.osType.listAll().toMap {it.code}.blockingGet())
	}
}
