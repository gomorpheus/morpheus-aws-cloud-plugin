package com.morpheusdata.aws

import com.amazonaws.services.elasticloadbalancingv2.model.TargetGroup
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.providers.StorageProvider
import com.morpheusdata.core.providers.StorageProviderBuckets
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.core.util.SyncList
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.SyncTask.UpdateItemDto
import com.morpheusdata.model.AccountCredential
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.StorageServerType
import com.morpheusdata.model.StorageBucket
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.projection.StorageBucketIdentityProjection
import com.morpheusdata.response.ServiceResponse
import com.bertramlabs.plugins.karman.StorageProvider as KarmanProvider
import com.bertramlabs.plugins.karman.Directory
import groovy.util.logging.Slf4j
import io.reactivex.Observable

@Slf4j
class S3StorageProvider implements StorageProvider, StorageProviderBuckets, CloudInitializationProvider {
    MorpheusContext morpheusContext
    AWSPlugin plugin

    public static final PROVIDER_CODE = "amazons3"

    public S3StorageProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
        this.plugin = plugin
        this.morpheusContext = morpheusContext
    }

    @Override
    MorpheusContext getMorpheus() {
        return morpheusContext
    }

    @Override
    Plugin getPlugin() {
        return plugin
    }

    @Override
    String getCode() {
        return PROVIDER_CODE
    }

    @Override
    String getName() {
        return "AWS S3"
    }

    @Override
    String getDescription() {
        return "Amazon S3 Services"
    }

    @Override
    Icon getIcon() {
        return null
    }

    /**
     * Provides a {@link StorageServerType} record that needs to be configured in the morpheus environment.
     * This record dicates configuration settings and other facets and behaviors for the storage type.
     * @return a {@link StorageServerType}
     */
    @Override
    StorageServerType getStorageServerType() {
        StorageServerType storageServerType = new StorageServerType(
            code:PROVIDER_CODE, name:getName(), description:getDescription(), hasBlock:false, hasObject:true, 
            hasFile:false, hasDatastore:false, hasNamespaces:false, hasGroups:false, hasDisks:true, hasHosts:false,
            createBlock:false, createObject:true, createFile:false, createDatastore:true, createNamespaces:false, 
            createGroup:false, createDisk:false, createHost:false, hasFileBrowser: true)
        storageServerType.optionTypes = getStorageServerOptionTypes()
        storageServerType.volumeTypes = getVolumeTypes()
        storageServerType.bucketOptionTypes = getStorageBucketOptionTypes()
        return storageServerType
    }
    
    /**
     * Provide custom configuration options when creating a new {@link StorageServer}
     * @return a List of OptionType
     */
    Collection<OptionType> getStorageServerOptionTypes() {
        return [
            new OptionType(code: 'storageServer.amazon.credential', name: 'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName: 'type', fieldLabel: 'Credentials', fieldContext: 'credential', required: true, displayOrder: 10, defaultValue: 'local',optionSource: 'credentials',config: '{"credentialTypes":["access-key-secret"]}'),
            new OptionType(code: 'storageServer.amazon.serviceUsername', name: 'Access Key', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUsername', fieldLabel: 'Access Key', fieldContext: 'domain', required: true, displayOrder: 11,localCredential: true),
            new OptionType(code: 'storageServer.amazon.servicePassword', name: 'Secret Key', inputType: OptionType.InputType.PASSWORD, fieldName: 'servicePassword', fieldLabel: 'Secret Key', fieldContext: 'domain', required: true, displayOrder: 12,localCredential: true),
            new OptionType(code: 'storageServer.amazon.serviceUrl', name: 'Endpoint', inputType: OptionType.InputType.TEXT , fieldName: 'serviceUrl', fieldLabel: 'Endpoint', fieldContext: 'domain', required: false, displayOrder: 13)
        ]
    }

    /**
     * Provides a collection of {@link VolumeType} records that needs to be configured in the morpheus environment
     * @return Collection of StorageVolumeType
     */
    Collection<StorageVolumeType> getVolumeTypes() {
        return [
            new StorageVolumeType(code:'s3Object', displayName:'AWS S3 Object Storage', name:'AWS S3 Object Storage', description:'AWS S3 Object Storage', volumeType:'bucket', enabled:false, displayOrder:1, customLabel:true, customSize:true, defaultType:true, autoDelete:true, minStorage:ComputeUtility.ONE_GIGABYTE, maxStorage:(10L * ComputeUtility.ONE_TERABYTE), allowSearch:true, volumeCategory:'volume')
        ]
    }

    /**
     * Provide custom configuration options when creating a new {@link StorageBucket}
     * @return a List of OptionType
     */
    Collection<OptionType> getStorageBucketOptionTypes() {
        return [
            new OptionType(code: 'storageBucket.amazon.bucketName', name: 'Bucket Name', inputType: OptionType.InputType.TEXT, fieldName: 'bucketName', fieldLabel: 'Bucket Name', fieldContext: 'domain', required: true, displayOrder: 1, editable: false, fieldCode:'gomorpheus.label.bucketName'),
            new OptionType(code: 'storageBucket.amazon.createBucket', name: 'Create Bucket', inputType: OptionType.InputType.CHECKBOX, fieldName: 'createBucket', fieldLabel: 'Create Bucket', fieldContext: 'config', required: false, displayOrder: 2, fieldCode:'gomorpheus.label.createBucket'),
            new OptionType(code: 'storageBucket.amazon.region', name: 'Region', inputType: OptionType.InputType.SELECT, optionSourceType: 'amazon', optionSource: 's3Regions', fieldName: 'region', fieldLabel: 'Region', fieldContext: 'config', required: true, displayOrder: 3, editable: true, fieldCode:'gomorpheus.label.region', visibleOnCode:'storageProvider.config.createBucket:on', dependsOnCode:'storageProvider.storageServer'),
            new OptionType(code: 'storageBucket.amazon.endpoint', name: 'Endpoint URL', inputType: OptionType.InputType.TEXT , fieldName: 'endpoint', fieldLabel: 'Endpoint URL', fieldContext: 'config', required: false, displayOrder: 4, editable: true, fieldCode:'gomorpheus.label.endpointUrl', helpTextI18nCode:'gomorpheus.help.endpointUrl')
        ]
    }

    @Override
    ServiceResponse initializeProvider(Cloud cloud) {
        log.debug("Initializing storage provider for ${cloud.name}")
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            StorageServer storageServer = new StorageServer(
                name: cloud.name,
                type: getStorageServerType()
            )
            morpheus.async.integration.registerCloudIntegration(cloud.id, storageServer).blockingGet()
            rtn.success = true
        } catch (Exception e) {
            rtn.success = false
            log.error("initializeProvider error: {}", e, e)
        }

        return rtn
    }

    ServiceResponse verifyStorageServer(StorageServer storageServer, Map opts) {
        log.debug("verifyStorageServer: {}", storageServer)
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            if(storageServer.refType !='ComputeZone') {
                String accessKey = storageServer.credentialData?.username ?: storageServer.serviceUsername
                String secretKey = storageServer.credentialData?.password ?: storageServer.servicePassword
                Boolean invalid = false
                if(!accessKey) {
                    rtn.errors += [serviceUsername: 'access key is required']
                    invalid = true
                }
                if(!secretKey) {
                    rtn.errors += [servicePassword: 'secret key is required']
                    invalid = true
                }
                if(invalid) {
                    return rtn
                }
            }
            def results = listBuckets(storageServer,opts)
            rtn.success = results.success
            if(!rtn.success) {
                rtn.msg = "Error authenticating / connecting to aws s3"
            }
        } catch(e) {
            log.error("verifyStorageServer error: ${e}", e)
            rtn.msg = 'Error connecting to aws s3'
        }
        return rtn
    }


    ServiceResponse initializeStorageServer(StorageServer storageServer, Map opts) {
        log.debug("initializeStorageServer: ${storageServer.name}")
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            if(storageServer) {
                rtn = refreshStorageServer(storageServer, opts)
            } else {
                rtn.msg = 'No storage server found'
            }
        } catch(e) {
            log.error("initializeStorageServer error: ${e}", e)
        }
        return rtn
    }

    ServiceResponse refreshStorageServer(StorageServer storageServer, Map opts) {
         ServiceResponse rtn = ServiceResponse.prepare()
         log.debug("refreshStorageServer: ${storageServer.name}")
         try {
            def syncDate = new Date()
            def hostOnline = true
            if(hostOnline) {
                cacheBuckets(storageServer, opts)
                //done update status
                //morpheus.storageServer.updateStorageServerStatus(storageServer, 'ok').subscribe().dispose()
                rtn.success = true
            } else {
                log.warn("refresh - storageServer: ${storageServer.name} - Storage server appears to be offline")
                rtn.msg = "Storage server appears to be offline"
                //morpheus.storageServer.updateStorageServerStatus(storageServer, 'error', "AWS S3 storage server ${storageServer.name} not reachable").subscribe().dispose()
            }
         } catch(e) {
            log.error("refreshStorageServer error: ${e}", e)
            rtn.msg = e.message
            //morpheus.storageServer.updateStorageServerStatus(storageServer, 'error', e.message).subscribe().dispose()
         }
         return rtn
    }

    def cacheBuckets(StorageServer storageServer, opts) {
        log.debug("cacheBuckets() ${storageServer.name}")
        // This is not region scoped, so we only need to use the first region to get all the buckets for all regions in the aws partition
        String endpoint
        String region
        Cloud cloud = getStorageServerCloud(storageServer)
        if(cloud) {
            Collection<String> regions = morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).toList().blockingGet().collect { it ->
                it.externalId
            } as Collection<String>
            // Use us-east-1 as the default, else the first region in the cloud
            region = regions.find { it == "us-east-1" } ?: regions?.getAt(0)
            //endpoint = "s3.${region}.amazonaws.com"
        } else {
            endpoint = storageServer.serviceUrl ?: "s3.us-east-1.amazonaws.com"
            region = endpoint.contains('amazonaws.com') ? AmazonComputeUtility.getAmazonEndpointRegion(endpoint) : null
        }
        def apiConfig = getApiConfig(storageServer, region)
        // log.debug("Caching buckets for storageServer: ${storageServer.name}, endpoint: ${apiConfig.endpoint}, region: ${apiConfig.region}")
        
        def bucketResults = listBuckets(storageServer, opts, apiConfig)
        if(bucketResults.success) {
            log.debug("Found ${bucketResults.buckets?.size()} buckets in S3 to sync for storage server ${storageServer.name}")
            // for(bucketResult in bucketResults.buckets) {
            //     log.info("Bucket Result: ${bucketResult.name} ${bucketResult}")
            // }

            // convert aws bucket object to a Map
            // List apiItems = bucketResults.buckets as List<Map>
            List apiItems = bucketResults.buckets.collect { b ->
                [
                    name: b.name
                ]
            } as List<Map>
            
            // Do not filter buckets by region, get all of the buckets for the storage server
            // Observable<StorageBucketIdentityProjection> existingBuckets = morpheusContext.async.storageBucket.listIdentityProjections(storageServer, region)
            Observable<StorageBucketIdentityProjection> existingBuckets = morpheusContext.async.storageBucket.listIdentityProjections(storageServer)

            SyncTask<StorageBucketIdentityProjection,Map,StorageBucket> syncTask = new SyncTask(existingBuckets, apiItems as Collection<Map>)
            syncTask.addMatchFunction { StorageBucketIdentityProjection domainObject, Map apiItem ->
                domainObject.externalId == apiItem['name']
            }.onDelete {removeItems ->
                while (removeItems?.size() > 0) {
                    List chunkedRemoveList = removeItems.take(50)
                    removeItems = removeItems.drop(50)
                    removeMissingBuckets(chunkedRemoveList)
                }
            }.onAdd { itemsToAdd ->
                while (itemsToAdd?.size() > 0) {
                    List chunkedAddList = itemsToAdd.take(50)
                    itemsToAdd = itemsToAdd.drop(50)
                    addMissingBuckets(storageServer,chunkedAddList,apiConfig)
                }
            }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<StorageBucketIdentityProjection,Map>> updateItems ->
                Map<Long, SyncTask.UpdateItemDto<StorageBucketIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                return morpheus.async.storageBucket.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { StorageBucket storageBucket ->
                    SyncTask.UpdateItemDto<StorageBucketIdentityProjection, Map> matchItem = updateItemMap[storageBucket.id]
                    return new SyncTask.UpdateItem<StorageBucket,Map>(existingItem:storageBucket, masterItem:matchItem.masterItem)
                }
            }.onUpdate { List<SyncTask.UpdateItem<StorageBucket,Map>> updateItems ->
                while (updateItems?.size() > 0) {
                    List chunkedUpdateList = updateItems.take(50)
                    updateItems = updateItems.drop(50)
                    updateMatchedBuckets(storageServer, chunkedUpdateList, apiConfig)
                }
            }.start()
        }
        return
    }

    protected void addMissingBuckets(StorageServer storageServer, List addList, Map apiConfig) {
        for(bucket in addList) {
            // configure
            def addConfig = [
                name:bucket.name, storageServer:storageServer, bucketName:bucket.name, externalId:bucket.name, 
                providerType:'s3', refType:'StorageServer', providerCategory:'object', refId:storageServer.id, 
                account:storageServer.account]
            StorageBucket storageBucket = new StorageBucket(addConfig)
            //JD: why are we storing credential config on each bucket?
            def providerConfig = [
                accessKey:apiConfig.accessKey, secretKey:apiConfig.secretKey, stsAssumeRole:apiConfig.stsAssumeRole,
                useHostCredentials:apiConfig.useHostCredentials, endpoint:apiConfig.endpoint, 
                region: apiConfig.region]
            storageBucket.setConfigMap(providerConfig)
            // need to make another api request to get bucket region
            // todo: make region lookup async and/or on demand.
            setBucketLocation(storageBucket, apiConfig)
            // save
            StorageBucket savedBucket = morpheusContext.async.storageBucket.create(storageBucket).blockingGet()
            if (!savedBucket) {
                log.error "Error creating storage bucket ${storageBucket.name} for storage server ${storageServer.name}"
            } else {
                log.debug "Added storage bucket ${storageBucket.name} for storage server ${storageServer.name}"
                // post save sync..
            }
        }
    }

    protected void updateMatchedBuckets(StorageServer storageServer, List updateList, Map apiConfig) {
        def updates = []
        for(update in updateList) {
            def bucket = update.masterItem
            StorageBucket existingBucket = update.existingItem
            if(existingBucket) {
                def bucketConfig = existingBucket.getConfigMap()
                def doUpdate = false
                if(existingBucket.storageServer == null) {
                    existingBucket.storageServer = storageServer
                    doUpdate = true
                }
                if(existingBucket.refType == 'ComputeZone') {
                    existingBucket.refType = 'StorageServer'
                    existingBucket.refId = storageServer.id
                    doUpdate = true
                }
                if(existingBucket.bucketName != bucket.name) {
                    existingBucket.bucketName = bucket.name
                    doUpdate = true
                }
                if(existingBucket.providerCategory != 'object') {
                    existingBucket.providerCategory = 'object'
                    doUpdate = true
                }
                // need to make another api request to get bucket region (only done if not already set)
                if(!existingBucket.regionCode) {
                    setBucketLocation(existingBucket, apiConfig)
                    doUpdate = true
                }
                //JD: why are we storing credential config on each bucket?
                // Should probably desist from disseminating this connection info to all the buckets
                if(apiConfig.endpoint && !apiConfig.endpoint.contains('amazonaws.com')) {
                    if(apiConfig.endpoint != bucketConfig.endpoint) {
                        existingBucket.setConfigProperty('endpoint', apiConfig.endpoint)
                        doUpdate = true
                    }
                }
                // if(apiConfig.region != bucketConfig.region) {
                //     existingBucket.setConfigProperty('region', apiConfig.region)
                //     doUpdate = true
                // }
                if(apiConfig.accessKey != bucketConfig.accessKey) {
                    existingBucket.setConfigProperty('accessKey', apiConfig.accessKey)
                    doUpdate = true
                }
                if(apiConfig.secretKey != bucketConfig.secretKey) {
                    existingBucket.setConfigProperty('secretKey', apiConfig.secretKey)
                    doUpdate = true
                }
                if(apiConfig.useHostCredentials != bucketConfig.useHostCredentials) {
                    existingBucket.setConfigProperty('useHostCredentials', apiConfig.useHostCredentials)
                    doUpdate = true
                }
                if(apiConfig.stsAssumeRole != bucketConfig.stsAssumeRole) {
                    existingBucket.setConfigProperty('stsAssumeRole', apiConfig.stsAssumeRole)
                    doUpdate = true
                }
                if(doUpdate) {
                    // log.debug("bucket needs updating id: ${existingBucket.id} name: ${existingBucket.name}, regionCode: ${existingBucket.regionCode}, bucketConfig: ${bucketConfig.inspect()}, config: ${existingBucket.config}")
                    updates << existingBucket
                } else {
                    //log.debug("bucket does NOT need updating id: ${existingBucket.id} name: ${existingBucket.name}, regionCode: ${existingBucket.regionCode}, bucketConfig: ${bucketConfig.inspect()}, config: ${existingBucket.config}")
                }
            }
        }
        if(updates) {
            log.debug("Updating ${updates.size()} storage buckets")
            morpheusContext.async.storageBucket.save(updates).blockingGet()
        }
    }

    protected removeMissingBuckets(List removeList) {
        if(removeList.size() > 0) {
            log.debug("Removing ${removeList.size()} missing buckets: ${removeList.collect {it.externalId}}")
            //Use removeForSync() to skip the delete if in use by a VirtualImage,Backup or DeploymentVersion
            //morpheus.async.storageBucket.remove(removeList).blockingGet()
            morpheus.async.storageBucket.removeForSync(removeList).blockingGet()
        }
    }

    protected Map listBuckets(StorageServer storageServer, opts, apiConfig=null) {
        def rtn = [success:false, buckets:[]]
        def provider
        try {
            apiConfig = apiConfig ?: getApiConfig(storageServer)
            //get the region
            // ?
            //provider config
            //log.debug("listBuckets API Config: ${apiConfig}")
            provider = getKarmanProvider(apiConfig)
            rtn.buckets = provider.getDirectories()
            rtn.success = true
        } catch(e) {
            log.error("listBuckets for storage server ${storageServer.id} error: ${e}", e)
        } finally {
            if(provider) {
                provider.shutdown()
            }
        }
        return rtn
    }

    ServiceResponse validateBucket(StorageBucket storageBucket, Map opts = [:]) {
        //todo: validate bucketName is set and not already in use maybe?
        return ServiceResponse.success()
    }

    ServiceResponse createBucket(StorageBucket storageBucket, Map opts = [:]) {
        def rtn = [success:false, bucket:null]
        KarmanProvider provider
        try {
            StorageServer storageServer = storageBucket.storageServer
            // storageBucket.refType = 'StorageServer'
            // storageBucket.refId = storageBucket.storageServer?.id
            def region = storageBucket.regionCode ?: storageBucket.getConfigProperty('region')
            def apiConfig = getApiConfig(storageServer, region)
            provider = getKarmanProvider(apiConfig)
            Directory directory = provider.getDirectory(storageBucket.bucketName)
            directory.save()
            if(region) {
                storageBucket.regionCode = region
                storageBucket.setConfigProperty('region', region)
                // set the endpoint based on the region
                def endpoint = region.startsWith("cn") ? "s3.${region}.amazonaws.com.cn" : "s3.${region}.amazonaws.com"
                storageBucket.setConfigProperty('endpoint', endpoint)
            }
            //JD: why are we storing credential config on each bucket?
            storageBucket.setConfigProperty('accessKey',apiConfig.accessKey) 
            storageBucket.setConfigProperty('secretKey',apiConfig.secretKey)
            storageBucket.externalId = storageBucket.bucketName
            //storageBucket.save(flush:true)
            StorageBucket savedBucket = morpheusContext.async.storageBucket.create(storageBucket).blockingGet()
            if (!savedBucket) {
                log.error "Error creating storage bucket ${storageBucket.name} for storage server ${storageServer.name}"
            } else {
                rtn.success = true
                rtn.data = savedBucket
                log.debug "Created storage bucket ${storageBucket.name} for storage server ${storageServer.name}"
                // post save sync..
            }
        } catch(e) {
            log.error("error creating bucket: ${e}", e)
            rtn.msg = e.message ?: 'unknown error creating bucket'
        } finally {
            if(provider) {
                provider.shutdown()
            }
        }
        return ServiceResponse.create(rtn)
    }

    ServiceResponse updateBucket(StorageBucket storageBucket, Map opts = [:]) {
        def rtn = [success:false, bucket:null]
        try {
            rtn.bucket = storageBucket
            rtn.success = true
        } catch(e) {
            log.error("error updating bucket: ${e}", e)
            rtn.msg = 'unknown error updating bucket'
        }
        return ServiceResponse.create(rtn)
    }


    ServiceResponse deleteBucket(StorageBucket storageBucket, Map opts = [:]) {
        def rtn = [success:false, bucket:null]
        KarmanProvider provider
        try {
            StorageServer storageServer = storageBucket.storageServer
            def region = storageBucket.regionCode ?: storageBucket.getConfigProperty('region')
            def apiConfig = getApiConfig(storageServer, region)
            provider = getKarmanProvider(apiConfig)
            Directory directory = provider.getDirectory(storageBucket.bucketName)
            directory.delete()
            //storageBucket.enabled = false
            // delete and required db cleanup handled by appliance on success
            //morpheus.async.storageBucket.remove([storageBucket]).blockingGet()
            rtn.success = true
        } catch(e) {
            log.error("error removing bucket: ${e}", e)
            rtn.msg = 'unknown error removing bucket'
        } finally {
            if(provider) {
                provider.shutdown()
            }
        }
        return ServiceResponse.create(rtn)
    }

    Collection<String> getStorageBucketProviderTypes() {
        // todo: what is this for?
        ["s3"]
    }

    protected def getApiConfig(StorageServer storageServer,String desiredRegion = null) {
        String accessKey = storageServer.credentialData?.username ?: storageServer.serviceUsername
        String secretKey = storageServer.credentialData?.password ?: storageServer.servicePassword
        Boolean useHostCredentials
        String stsAssumeRole
        //JD: endpoint can be null, as long as region is set..
        String endpoint // = storageServer.serviceUrl
        String region
        Cloud zone
        def proxyOptions = [:]
        if(storageServer.refType == 'ComputeZone') {
            zone = getStorageServerCloud(storageServer)
            if(zone) {
                accessKey = accessKey ?: AmazonComputeUtility.getAmazonAccessKey(zone)
                secretKey = secretKey ?: AmazonComputeUtility.getAmazonSecretKey(zone)
                useHostCredentials = AmazonComputeUtility.getAmazonUseHostCredentials(zone)
                stsAssumeRole = zone.getConfigProperty('stsAssumeRole')
                if(desiredRegion) {
                    region = desiredRegion
                    endpoint = region.startsWith("cn") ? "s3.${region}.amazonaws.com.cn" : "s3.${region}.amazonaws.com"
                    
                } else {
                    endpoint = zone.regionCode ?: "s3.us-east-1.amazonaws.com"
                    if(endpoint.startsWith('ec2.')) {
                        endpoint = 's3.' + endpoint.substring(4)
                    }
                }


                if(zone.apiProxy) {
                    proxyOptions = [
                        proxyHost: zone.apiProxy.proxyHost,
                        proxyPort: zone.apiProxy.proxyPort,
                        proxyUser: zone.apiProxy.proxyUser,
                        proxyPassword: zone.apiProxy.proxyPassword,
                        proxyDomain: zone.apiProxy.proxyDomain,
                        proxyWorkstation: zone.apiProxy.proxyWorkstation
                    ]
                }   
            }
        } else {
            // stand alone storage server
            if(desiredRegion) {
                region = desiredRegion
                endpoint = region.startsWith("cn") ? "s3.${region}.amazonaws.com.cn" : "s3.${region}.amazonaws.com"
            } else {
                endpoint = storageServer.serviceUrl
            }
        }
        // determine region from endpoint if amazon s3 url, so that region is always set and used intead of endpoint
        if(!region && endpoint && endpoint.contains('amazonaws.com')) {
            region = AmazonComputeUtility.getAmazonEndpointRegion(endpoint)
        }
        
        return [accessKey:accessKey, secretKey:secretKey, endpoint:endpoint,region: region, zone:zone, stsAssumeRole:stsAssumeRole,
            useHostCredentials:useHostCredentials] + proxyOptions
    }

    protected KarmanProvider getKarmanProvider(Map apiConfig) {
        //provider config
        def providerMap = [provider:'s3', accessKey:apiConfig.accessKey, secretKey:apiConfig.secretKey, endpoint:apiConfig.endpoint, region:apiConfig.region]
        Cloud zone = apiConfig.zone
        if(zone?.apiProxy) {
            providerMap.proxyHost = zone.apiProxy.proxyHost
            providerMap.proxyPort = zone.apiProxy.proxyPort
            if(zone.apiProxy.proxyUser) {
                providerMap.proxyUser = zone.apiProxy.proxyUser
                providerMap.proxyPassword = zone.apiProxy.proxyPassword

            }
            providerMap.proxyDomain = zone.apiProxy.proxyDomain
            providerMap.proxyWorkstation = zone.apiProxy.proxyWorkstation
        }
        providerMap.useHostCredentials = AmazonComputeUtility.getAmazonUseHostCredentials(zone)
        providerMap.stsAssumeRole = zone?.getConfigProperty('stsAssumeRole')
        return KarmanProvider.create(providerMap)
    }

    protected Cloud getStorageServerCloud(StorageServer storageServer) {
        if(storageServer.refType == 'ComputeZone' || storageServer.refType == 'Cloud') {
            // this could return null too if refId is missing
            Cloud cloud = morpheus.cloud.getCloudById(storageServer.refId).blockingGet();
            return checkCloudCredentials(cloud)
        } else {
            return null
        }
    }

    // Should move to the common AWSPlugin() as getAmazonS3Client I suppose
    // for now just use our trusty AmazonComputeUtility

    // protected AmazonS3Client getAmazonS3Client(Cloud cloud, Boolean fresh = false, String region = null) {
    //     AmazonComputeUtility.getAmazonS3Client(checkCloudCredentials(cloud), fresh, region)
    // }


    // protected AmazonS3Client getAmazonS3Client(Map apiConfig, Boolean fresh = false, String region = null) {
    //     AmazonComputeUtility.getAmazonS3Client(apiConfig, fresh, region)
    // }

    protected Cloud checkCloudCredentials(Cloud cloud) {
        if(!cloud.accountCredentialLoaded) {
            AccountCredential accountCredential
            try {
                accountCredential = this.morpheus.async.cloud.loadCredentials(cloud.id).blockingGet()
            } catch(e) {
                // If there is no credential on the cloud, then this will error
                // TODO: Change to using 'maybe' rather than 'blockingGet'?
            }
            cloud.accountCredentialLoaded = true
            cloud.accountCredentialData = accountCredential?.data
        }
        return cloud
    }

    protected void setBucketLocation(StorageBucket storageBucket, Map apiConfig, Boolean refresh = false) {
        try {
            // If endpoint is not aws, regions not supported
            // if(apiConfig.endpoint && !apiConfig.endpoint.endsWith('amazonaws.com') && !apiConfig.endpoint.endsWith('amazonaws.com.cn')) {
            if(apiConfig.endpoint && !apiConfig.endpoint.contains('amazonaws.com')) {
                return
            }
            // This does an AWS API request and is kind of slow so try to do it just once, if the regionCode is null.
            // The config.region setting is already in use  by the zone or storageServer region (along with endpoint, accessKey, etc), which is not necessarily the same as the bucket region.
            // This populates the storageBucket.regionCode property with the bucket's region
            // and then sets region and endpoint, though it might need to stop doing that...
            // def region = storageBucket.getConfigProperty('region')
            String region = storageBucket.regionCode
            if(!region || refresh) {
                // todo: COULD maybe lookup region from bucket names that exist for overlapping clouds and storage servers

                //log.debug("Generating AmazonS3Client with apiConfig: ${apiConfig}")
                // \o/ HACK ALERT \o/
                // It appears if us-east-1 is used and the bucket is in another region,
                // Then the following error is seen: "The authorization header is malformed; the region 'us-east-1' is wrong"
                // However, using another region works, so use us-east-2 instead.
                // Maybe we need to use forceGlobalBucketAccess, which requires an upgrade of the AWS SDK
                if(apiConfig.region == "us-east-1") {
                    apiConfig = apiConfig.clone()
                    apiConfig.region = "us-east-2"
                    apiConfig.endpoint = "s3.us-east-2.amazonaws.com"
                }
                def amazonClient = AmazonComputeUtility.getAmazonS3Client(apiConfig, apiConfig.region)
                def bucketLocationResult = AmazonComputeUtility.getBucketLocation(amazonClient, storageBucket.bucketName)

                if(bucketLocationResult.success) {
                    region = bucketLocationResult.location
                    // GetBucketLocation requests return null for us-east-1 which the SDK then replaces with "US".
                    if(region == "US") {
                        region = "us-east-1"
                    }
                    log.debug("Determined region for storage server: ${storageBucket.storageServer?.name}, bucket id: ${storageBucket.id}, bucketName: ${storageBucket.bucketName}, region: ${region}")
                    storageBucket.regionCode = region
                    //todo: maybe skip setting region and endpoint and let that come from the storage server
                    storageBucket.setConfigProperty('region', region)
                    def endpoint = region.startsWith("cn") ? "s3.${region}.amazonaws.com.cn" : "s3.${region}.amazonaws.com"
                    storageBucket.setConfigProperty('endpoint', endpoint)
                }
                else {
                    log.warn("Failed to determine region for storage server: ${storageBucket.storageServer?.name}, bucket id: ${storageBucket.id}, bucketName: ${storageBucket.bucketName}, error: ${bucketLocationResult.msg}")
                }
            } else {
                // bucket already has region, no lookup needed
            }
        } catch(e) {
            log.error("An exception occured while determining the region for storage server: ${storageBucket.storageServer?.name}, bucket id: ${storageBucket.id}, bucketName: ${storageBucket.bucketName}, error: ${e}", e)
        }
    }
    
}
