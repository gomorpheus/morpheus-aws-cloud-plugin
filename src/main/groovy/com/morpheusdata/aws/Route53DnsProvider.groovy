package com.morpheusdata.aws

import com.morpheusdata.core.providers.DNSProvider
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.AccountIntegrationType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomain
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.projection.NetworkDomainIdentityProjection
import com.morpheusdata.model.projection.NetworkDomainRecordIdentityProjection
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j
import io.reactivex.Single
import io.reactivex.Observable

/**
 * The DNS Provider implementation for Amazon Route53 DNS service.
 * 
 * @author James Dickson
 */
@Slf4j
class Route53DnsProvider implements DNSProvider, CloudInitializationProvider {

	final String name = 'Route 53'
	final String code = 'amazonDns'

	MorpheusContext morpheusContext
    Plugin plugin

    Route53DnsProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

    /**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return plugin
	}

	/**
	 * Returns the DNS Integration logo for display when a user needs to view or add this integration
	 * @return Icon representation of assets stored in the src/assets of the project.
	 * @since 0.12.3
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:"amazon-route53.svg", darkPath: "amazon-route53-dark.svg")
	}

	@Override
	ServiceResponse initializeProvider(Cloud cloud) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			AccountIntegration integration = new AccountIntegration(
				name: cloud.name,
				type: new AccountIntegrationType(code:"amazonDns"),
				serviceUrl: cloud.regionCode
			)
			morpheus.integration.registerCloudIntegration(cloud.id, integration)
			ServiceResponse.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("initializeProvider error: {}", e, e)
		}

		return rtn
	}

	/**
	 * Creates a manually allocated DNS Record of the specified record type on the passed {@link NetworkDomainRecord} object.
	 * This is typically called outside of automation and is a manual method for administration purposes.
	 * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
	 * @param record The domain record that is being requested for creation. All the metadata needed to create teh record
	 *               should exist here.
	 * @param opts any additional options that may be used in the future to configure behavior. Currently unused
	 * @return a ServiceResponse with the success/error state of the create operation as well as the modified record.
	 */
	@Override
	ServiceResponse createRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
		log.debug("createRecord - Request record: ${record.getProperties()}")
        log.debug("createRecord - Request opts: ${opts}")
		try {
			if(integration) {
				def fqdn = record.name
				if(!record.name.endsWith(record.networkDomain.name)) {
					fqdn = "${record.name}.${record.networkDomain.name}"
				}

				def amazonClient = getAmazonRoute53Client(integration)
				def recordType = record.type
				def recordHost = record.content
				def region = integration.serviceUrl
				region = AmazonComputeUtility.getAmazonEndpointRegion(region)
				def externalId = "${record.type}-${fqdn}-${region}"
				def results = AmazonComputeUtility.createDnsRecord(amazonClient, fqdn, record.networkDomain.externalId, recordType, recordHost)
				log.info("provisionServer results: ${results}")
				if(results.success) {
					record.externalId = externalId
					log.info("createRecord - integration ${integration.name} - Successfully created ${record.type} record - host: ${record.name}, data: ${record.content}")
					return new ServiceResponse<NetworkDomainRecord>(true,"Successfully created ${record.type} record - host: ${record.name}, data: ${record.content}",null,record)
				} else {
					def rpcData = results.content
					log.warn("createRecord - integration: ${integration.name} - Failed to create Resource Record ${rpcData}")
                	return new ServiceResponse<NetworkDomainRecord>(false,"deleteRecord - integration: ${integration.name} - Failed to delete Resource Record ${rpcData}",null,record)
				}
			} else {
				log.warn("no integration")
				return ServiceResponse.error("System Error removing Amazon DNS Record ${record.name} - no integration")
			}
		} catch(e) {
			log.error("createRecord - integration: ${integration.name} error: ${e}", e)
            return ServiceResponse.error("System Error creating Amazon DNS Record ${record.name} - ${e.message}")
		}
	}

	/**
	 * Deletes a Zone Record that is specified on the Morpheus side with the target integration endpoint.
	 * This could be any record type within the specified integration and the authoritative zone object should be
	 * associated with the {@link NetworkDomainRecord} parameter.
	 * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
	 * @param record The zone record object to be deleted on the target integration.
	 * @param opts opts any additional options that may be used in the future to configure behavior. Currently unused
	 * @return the ServiceResponse with the success/error of the delete operation.
	 */
	@Override
	ServiceResponse deleteRecord(AccountIntegration integration, NetworkDomainRecord record, Map opts) {
		try {
			if(integration) {
				def fqdn = record.name
				if(!record.name.endsWith(record.networkDomain.name)) {
					fqdn = "${record.name}.${record.networkDomain.name}"
				}

				def amazonClient = getAmazonRoute53Client(integration)
				def recordType = record.type
				def recordHost = record.content
				def region = integration.serviceUrl
				region = AmazonComputeUtility.getAmazonEndpointRegion(region)
				//def externalId = "${record.type}-${fqdn}-${region}"
				def results = AmazonComputeUtility.deleteDnsRecord(amazonClient, fqdn, record.networkDomain.externalId, recordType, recordHost)
				log.info("provisionServer results: ${results}")
				if(results.success) {
					return new ServiceResponse<NetworkDomainRecord>(true,null,null,record)
				} else {
					def rpcData = results.content
					log.warn("deleteRecord - integration: ${integration.name} - Failed to delete Resource Record ${rpcData}")
                	return new ServiceResponse<NetworkDomainRecord>(false,"deleteRecord - integration: ${integration.name} - Failed to delete Resource Record ${rpcData}",null,record)
				}
			} else {
				log.warn("no integration")
				return ServiceResponse.error("System Error removing Amazon DNS Record ${record.name} - no integration")
			}
		} catch(e) {
			log.error("deleteRecord - integration: ${integration.name} error: ${e}", e)
            return ServiceResponse.error("System Error removing Amazon DNS Record ${record.name} - ${e.message}")
		}
	}

	/**
	 * Provide custom configuration options when creating a new {@link AccountIntegration}
	 * @return a List of OptionType
	 */
	@Override
	List<OptionType> getIntegrationOptionTypes() {
		return null
	}

	/**
	 * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
	 * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
	 * cycle by default. Useful for caching DNS Records created outside of Morpheus.
	 * NOTE: This method is unused when paired with a DNS Provider so simply return null
	 * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
	 */
	@Override
	void refresh(AccountIntegration integration) {
		try {
            def hostOnline = true //todo: actually check if route 53 is reachable
            if(hostOnline) {
                Date now = new Date()
                cacheZones(integration)
                cacheZoneRecords(integration)
                log.info("refresh - integration: ${integration.name} - Sync Completed in ${new Date().time - now.time}ms")
                //JD: do not update status for zone integration only stand alone dns integrations
                if(isStandAloneIntegration(integration)) {
                	morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.ok).subscribe().dispose()
                }
            } else {
                log.warn("refresh - integration: ${integration.name} - Integration appears to be offline")
                if(isStandAloneIntegration(integration)) {
                	morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.error, "Route 53 integration ${integration.name} not reachable")
                }
            }
        } catch(e) {
            log.error("refresh - integration ${integration.name} - Error: ${e}", e)
        }
	}


	// Cache Zones methods
	def cacheZones(AccountIntegration integration, Map opts = [:]) {
		def amazonEc2Client = getAmazonClient(integration, false, settingsService.getKarmanProxySettings())
		def regionResults = AmazonComputeUtility.listRegions([amazonClient:amazonEc2Client])
		def regionList = regionResults.regionList
		if(integration.serviceUrl) {
			//we are scoped to a region so filter
			regionList = regionList.findAll{it.getRegionName()==AmazonComputeUtility.getAmazonEndpointRegion(integration.serviceUrl)}
		}
		regionList?.each { region ->
			def regionCode = region.getRegionName()
			def amazonClient = getAmazonRoute53Client(integration, regionCode)
			def hostedZones = AmazonComputeUtility.listDnsHostedZones(amazonClient)
			//log.debug("zones: ${hostedZones}")
			if(hostedZones.success) {
				// def syncItems = hostedZones?.zoneList
				// def existingItems = loadIntegrationDomains(integration,regionCode)
				// def matchFunction = { NetworkDomain existingItem, Object syncItem ->
				// 	existingItem.externalId == syncItem.getId()
				// }
				
				// def listResults = listZones(integration)
				def listResults = hostedZones

            	// convert aws dns zone object to a Map
                // List apiItems = listResults.zoneList as List<Map>
                List apiItems = listResults.zoneList.collect { zone ->
                	[
                		id: zone.getId(), 
                		name: zone.getName(),
                		description: zone.getConfig()?.getComment(),
                		publicZone: zone.getConfig()?.isPrivateZone() != true,
                		regionCode: regionCode
                	]
                } as List<Map>
                

                // need to filter to just this region
                // Observable<NetworkDomainIdentityProjection> domainRecords = morpheus.network.domain.listIdentityProjections(integration.id).filter { NetworkDomainIdentityProjection projection ->
				// 	return (projection.regionCode == regionCode)
				// }
				// added new listIdentityProjections(refId, region) to scope by regionCode
                Observable<NetworkDomainIdentityProjection> domainRecords = morpheus.network.domain.listIdentityProjections(integration.id, regionCode)
                // def networkDomains = morpheusContext.network.domain.listById(domainRecords.collect {it.id}).toList().blockingGet()

                SyncTask<NetworkDomainIdentityProjection,Map,NetworkDomain> syncTask = new SyncTask(domainRecords, apiItems as Collection<Map>)
                syncTask.addMatchFunction { NetworkDomainIdentityProjection domainObject, Map apiItem ->
                    domainObject.externalId == apiItem['id']
                }.onDelete {removeItems ->
                    morpheus.network.domain.remove(integration.id, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingZones(integration, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainIdentityProjection,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomain networkDomain ->
                        SyncTask.UpdateItemDto<NetworkDomainIdentityProjection, Map> matchItem = updateItemMap[networkDomain.id]
                        return new SyncTask.UpdateItem<NetworkDomain,Map>(existingItem:networkDomain, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomain,Map>> updateItems ->
                    updateMatchedZones(integration, updateItems)
                }.start()

			}
		}

	}
    


    /**
     * Creates a mapping for networkDomainService.createSyncedNetworkDomain() method on the network context.
     * @param integration
     * @param addList
     */
    void addMissingZones(AccountIntegration integration, Collection addList) {
        List<NetworkDomain> missingZonesList = addList?.collect { Map zone ->
            NetworkDomain networkDomain = new NetworkDomain()
            networkDomain.externalId = zone['id']
            networkDomain.name = NetworkUtility.getFriendlyDomainName(zone['name'] as String)
            networkDomain.fqdn = NetworkUtility.getFqdnDomainName(zone['name'] as String)
            networkDomain.description = zone['description']
            networkDomain.refSource = 'integration'
            networkDomain.zoneType = 'Authoritative'
            networkDomain.publicZone = zone['publicZone']
            networkDomain.regionCode = zone['regionCode']
            log.info("Adding Zone: ${networkDomain}")
            return networkDomain
        }
        morpheus.network.domain.create(integration.id, missingZonesList).blockingGet()
    }

    /**
     * Given an AccountIntegration (integration) and updateList, update NetwordDomain zone records
     * @param integration
     * @param updateList
     */
    void updateMatchedZones(AccountIntegration integration, List<SyncTask.UpdateItem<NetworkDomain,Map>> updateList) {
        def domainsToUpdate = []
        log.info("updateMatchedZones -  update Zones for ${integration.name} - updated items ${updateList.size()}")
        for(SyncTask.UpdateItem<NetworkDomain,Map> update in updateList) {
            NetworkDomain existingItem = update.existingItem as NetworkDomain
            if(existingItem) {
                Boolean save = false
                if(!existingItem.externalId) {
                    existingItem.externalId = update.masterItem['id']
                    save = true
                }

                if(!existingItem.refId) {
                    existingItem.refType = 'AccountIntegration'
                    existingItem.refId = integration.id
                    existingItem.refSource = 'integration'
                    save = true
                }

                if(save) {
                    log.info("updateMatchedZones -  ready to update item ${existingItem}")
                    domainsToUpdate.add(existingItem)
                }
            }
        }
        if(domainsToUpdate.size() > 0) {
            morpheus.network.domain.save(domainsToUpdate).blockingGet()
        }
    }


    // Cache Zone Records methods
    def cacheZoneRecords(AccountIntegration integration, Map opts=[:]) {

        morpheus.network.domain.listIdentityProjections(integration.id).buffer(50).flatMap { Collection<NetworkDomainIdentityProjection> resourceIdents ->
            return morpheus.network.domain.listById(resourceIdents.collect{it.id})
        }.flatMap { NetworkDomain domain ->
        	def amazonClient = getAmazonRoute53Client(integration, domain.regionCode)
            // def listResults = listRecords(integration,domain)
            def listResults = AmazonComputeUtility.listDnsZoneRecords(amazonClient, domain.externalId)
            //todo: change to log.debug, or maybe do not log entire results eh?
            log.info("cacheZoneRecords - domain: ${domain.externalId}, listResults: ${listResults}")

            if (listResults.success) {
                List<Map> apiItems = listResults.recordList.collect { record ->
                	def addConfig = [
                		networkDomain:domain, 
                		fqdn:NetworkUtility.getDomainRecordFqdn(record.name, domain.fqdn),
						type:record.type?.toUpperCase(), 
						ttl:record.ttl, 
						externalId:record.externalId, 
						source:'sync',
						recordData:record.recordData, 
						content:record.recordsData?.join('\n'),
						regionCode: domain.regionCode
					]
					if(addConfig.type == 'SOA' || addConfig.type == 'NS')
						addConfig.name = record.name
					else
						addConfig.name = NetworkUtility.getFriendlyDomainName(record.name)
					// def add = new NetworkDomainRecord(addConfig)
					addConfig
                } as List<Map>

                //Unfortunately the unique identification matching for msdns requires the full record for now... so we have to load all records...this should be fixed

                Observable<NetworkDomainRecord> domainRecords = morpheus.network.domain.record.listIdentityProjections(domain,null).buffer(50).flatMap {domainIdentities ->
                    morpheus.network.domain.record.listById(domainIdentities.collect{it.id})
                }
                SyncTask<NetworkDomainRecord, Map, NetworkDomainRecord> syncTask = new SyncTask<NetworkDomainRecord, Map, NetworkDomainRecord>(domainRecords, apiItems)
                return syncTask.addMatchFunction {  NetworkDomainRecord domainObject, Map apiItem ->
                    domainObject.externalId == apiItem['externalId']

                }.onDelete {removeItems ->
                    morpheus.network.domain.record.remove(domain, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                    addMissingDomainRecords(domain, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainRecord,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainRecord, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.record.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomainRecord domainRecord ->
                        SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map> matchItem = updateItemMap[domainRecord.id]
                        return new SyncTask.UpdateItem<NetworkDomainRecord,Map>(existingItem:domainRecord, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomainRecord,Map>> updateItems ->
                    updateMatchedDomainRecords(updateItems)
                }.observe()
            } else {
                log.info("cacheZoneRecords - No data to sync for ${domain.externalId}")
                return Single.just(false)
            }
        }.doOnError{ e ->
            log.error("cacheZoneRecords error: ${e}", e)
        }.subscribe()

    }


    void updateMatchedDomainRecords(List<SyncTask.UpdateItem<NetworkDomainRecord, Map>> updateList) {
        def records = []
        updateList?.each { update ->
            NetworkDomainRecord existingItem = update.existingItem
            if(existingItem) {
                //update view ?
                def save = false
                // what is this for?
                // if(existingItem.externalId == null) {
                //     existingItem.externalId = update.masterItem['externalId']
                //     existingItem.internalId = update.masterItem['content']
                //     save = true
                // }
                if(update.masterItem['content'] != update.masterItem['content'] && update.masterItem['content']) {
                    existingItem.content = update.masterItem['content']
                    save = true
                }

                if(save) {
                    records.add(existingItem)
                }
            }
        }
        if(records.size() > 0) {
            morpheus.network.domain.record.save(records).blockingGet()
        }
    }

    void addMissingDomainRecords(NetworkDomain domain, Collection<Map> addList) {
        List<NetworkDomainRecord> records = []

        addList?.each {record ->
            if(record['HostName']) {
                def addConfig = [networkDomain:new NetworkDomain(id: domain.id), fqdn:record['fqdn'],
                                 type:record['type']?.toUpperCase(), comments:record['comments'], ttl:record['ttl'],
                                 externalId:record['externalId'], internalId:record['content'], source:'sync',
                                 recordData:record['recordData'], content:record['content']]
                if(addConfig.type == 'SOA' || addConfig.type == 'NS')
                    addConfig.name = record['name']
                else
                    addConfig.name = NetworkUtility.getFriendlyDomainName(record['name'] as String)
                def add = new NetworkDomainRecord(addConfig)
                records.add(add)
            }

        }
        morpheus.network.domain.record.create(domain,records).blockingGet()
    }

	/**
	 * Validation Method used to validate all inputs applied to the integration of an DNS Provider upon save.
	 * If an input fails validation or authentication information cannot be verified, Error messages should be returned
	 * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
	 * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
	 * NOTE: This is unused when paired with an IPAMProvider interface
	 * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
	 * @param opts any custom payload submission options may exist here
	 * @return A response is returned depending on if the inputs are valid or not.
	 */
	@Override
	ServiceResponse verifyAccountIntegration(AccountIntegration integration, Map opts) {
		ServiceResponse rtn = new ServiceResponse()
		//todo
		rtn.success = true
		return rtn
	}

	def getAmazonRoute53Client(AccountIntegration integration, String region=null) {
		AmazonComputeUtility.getAmazonRoute53Client(integration, false, null, [:], region)
	}

	def getAmazonClient(AccountIntegration integration, String region=null) {
		def cloud = Cloud.get(integration.refId)
		AmazonComputeUtility.getAmazonClient(cloud, false, region)
	}

	Boolean isCloudIntegration(AccountIntegration integration) {
		integration.refType == 'ComputeZone' || integration.refType == 'Cloud'
	}

	Boolean isStandAloneIntegration(AccountIntegration integration) {
		!isCloudIntegration()
	}

}
