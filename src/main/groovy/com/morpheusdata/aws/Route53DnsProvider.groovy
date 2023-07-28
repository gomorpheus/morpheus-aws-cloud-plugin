package com.morpheusdata.aws

import com.morpheusdata.core.DNSProvider
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.core.util.NetworkUtility
import com.morpheusdata.aws.utils.AmazonComputeUtility
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
 */
@Slf4j
class Route53DnsProvider implements DNSProvider, CloudInitializationProvider {

	final String name = 'Route 53'
	final String code = 'amazonDns'

	MorpheusContext morpheus
    Plugin plugin

    Route53DnsProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheus = morpheusContext
        this.plugin = plugin
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
			morpheus.integration.registerCloudIntegration(cloud.id, integration).blockingGet()
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
				Cloud cloud = getIntegrationCloud(integration)
				def amazonClient = AmazonComputeUtility.getAmazonRoute53Client(integration, cloud)
				def recordType = record.type
				def recordHost = record.content
				//JD: The domain records in AWS have region == null, so this results in the record being created and then deleted and recreated on the next sync..yick
				// def region = record.networkDomain.regionCode ?: integration.serviceUrl
				// region = AmazonComputeUtility.getAmazonEndpointRegion(region)
				def region = null
				def externalId = "${record.type}-${fqdn}-${region}"
				def results = AmazonComputeUtility.createDnsRecord(amazonClient, fqdn, record.networkDomain.externalId, recordType, recordHost)
				log.debug("createDnsRecord results: ${results}")
				if(results.success) {
					record.externalId = externalId
					log.info("createRecord - integration ${integration.name} - Successfully created ${record.type} record - host: ${record.name}, data: ${record.content}")
					return new ServiceResponse<NetworkDomainRecord>(true,"Successfully created ${record.type} record - host: ${record.name}, data: ${record.content}",null,record)
				} else {
					log.warn("createRecord - integration: ${integration.name} - Failed to create Resource Record")
                	return new ServiceResponse<NetworkDomainRecord>(false,"createRecord - integration: ${integration.name} - Failed to create Resource Record",null,record)
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
				Cloud cloud = getIntegrationCloud(integration)
				def amazonClient = AmazonComputeUtility.getAmazonRoute53Client(integration, cloud)
				def recordType = record.type
				def recordHost = record.content
				def results = AmazonComputeUtility.deleteDnsRecord(amazonClient, fqdn, record.networkDomain.externalId, recordType, recordHost)
				log.info("deleteDnsRecord results: ${results}")
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
		return [
                new OptionType(code: 'accountIntegration.amazon.dns.serviceUrl', name: 'Region', inputType: OptionType.InputType.SELECT, optionSourceType: 'amazon', optionSource: 'amazonEndpoint', fieldName: 'serviceUrl', fieldLabel: 'Region', fieldContext: 'domain', required: false, displayOrder: 0),
                new OptionType(code: 'accountIntegration.amazon.dns.credentials', name: 'Credentials', inputType: OptionType.InputType.CREDENTIAL, fieldName: 'type', fieldLabel: 'Credentials', fieldContext: 'credential', required: true, displayOrder: 1, defaultValue: 'local',optionSource: 'credentials',config: '{"credentialTypes":["access-key-secret"]}'),

                new OptionType(code: 'accountIntegration.amazon.dns.serviceUsername', name: 'Access Key', inputType: OptionType.InputType.TEXT, fieldName: 'serviceUsername', fieldLabel: 'Access Key', fieldContext: 'domain', required: true, displayOrder: 2,localCredential: true),
                new OptionType(code: 'accountIntegration.amazon.dns.servicePassword', name: 'Secret Key', inputType: OptionType.InputType.PASSWORD, fieldName: 'servicePassword', fieldLabel: 'Secret Key', fieldContext: 'domain', required: true, displayOrder: 3,localCredential: true)
        ]
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
			Cloud cloud = getIntegrationCloud(integration)
            def hostOnline = true //todo: actually check if route 53 is reachable
            if(hostOnline) {
                Date now = new Date()
                cacheZones(integration)
                cacheZoneRecords(integration)
                log.info("refresh - integration: ${integration.name} - Sync Completed in ${new Date().time - now.time}ms")
                //JD: do not update status for zone integration only stand alone dns integrations
                if(!cloud) {
                	morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.ok).subscribe().dispose()
                }
            } else {
                log.warn("refresh - integration: ${integration.name} - Integration appears to be offline")
                if(!cloud) {
                	morpheus.integration.updateAccountIntegrationStatus(integration, AccountIntegration.Status.error, "Route 53 integration ${integration.name} not reachable")
                }
            }
        } catch(e) {
            log.error("refresh - integration ${integration.name} - Error: ${e}", e)
        }
	}


	// Cache Zones methods
	def cacheZones(AccountIntegration integration, Map opts = [:]) {
		Cloud cloud = getIntegrationCloud(integration)
		// Route53 DNS is scoped to aws partition, not individual regions
		def amazonClient = AmazonComputeUtility.getAmazonRoute53Client(integration, cloud)
		def listResults = AmazonComputeUtility.listDnsHostedZones(amazonClient)
		//log.debug("zones: ${hostedZones}")
		if(listResults.success) {
        	// convert aws dns zone object to a Map
            // List apiItems = listResults.zoneList as List<Map>
            List apiItems = listResults.zoneList.collect { zone ->
            	[
            		id: zone.getId(), 
            		name: zone.getName(),
            		description: zone.getConfig()?.getComment(),
            		publicZone: zone.getConfig()?.isPrivateZone() != true
            	]
            } as List<Map>
            
            Observable<NetworkDomainIdentityProjection> domains = morpheus.network.domain.listIdentityProjections(integration.id)

            SyncTask<NetworkDomainIdentityProjection,Map,NetworkDomain> syncTask = new SyncTask(domains, apiItems as Collection<Map>)
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
            log.info("Adding network domain: ${networkDomain.fqdn}")
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
        log.debug("updateMatchedZones -  update Zones for ${integration.name} - updated items ${updateList.size()}")
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
    	Cloud cloud = getIntegrationCloud(integration)
        morpheus.network.domain.listIdentityProjections(integration.id).buffer(50).flatMap { Collection<NetworkDomainIdentityProjection> resourceIdents ->
            return morpheus.network.domain.listById(resourceIdents.collect{it.id})
        }.flatMap { NetworkDomain domain ->
        	def amazonClient = AmazonComputeUtility.getAmazonRoute53Client(integration, cloud)
            // def listResults = listRecords(integration,domain)
            def listResults = AmazonComputeUtility.listDnsZoneRecords(amazonClient, domain.externalId)
            //todo: change to log.debug, or maybe do not log entire results eh?
            log.debug("cacheZoneRecords - domain: ${domain.externalId}, listResults: ${listResults}")

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
						content:record.recordsData?.join('\n')
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
                	log.debug("Removing ${removeItems.size()} network domain records for domain ${domain.externalId}")
                    morpheus.network.domain.record.remove(domain, removeItems).blockingGet()
                }.onAdd { itemsToAdd ->
                	log.debug("Adding ${itemsToAdd.size()} network domain records for domain ${domain.externalId}")
                    addMissingDomainRecords(domain, itemsToAdd)
                }.withLoadObjectDetails { List<SyncTask.UpdateItemDto<NetworkDomainRecord,Map>> updateItems ->
                    Map<Long, SyncTask.UpdateItemDto<NetworkDomainRecord, Map>> updateItemMap = updateItems.collectEntries { [(it.existingItem.id): it]}
                    return morpheus.network.domain.record.listById(updateItems.collect{it.existingItem.id} as Collection<Long>).map { NetworkDomainRecord domainRecord ->
                        SyncTask.UpdateItemDto<NetworkDomainRecordIdentityProjection, Map> matchItem = updateItemMap[domainRecord.id]
                        return new SyncTask.UpdateItem<NetworkDomainRecord,Map>(existingItem:domainRecord, masterItem:matchItem.masterItem)
                    }
                }.onUpdate { List<SyncTask.UpdateItem<NetworkDomainRecord,Map>> updateItems ->
                	log.debug("Updating ${updateItems.size()} network domain records for domain ${domain.externalId}")
                    updateMatchedDomainRecords(updateItems)
                }.observe()
            } else {
                log.debug("cacheZoneRecords - No data to sync for ${domain.externalId}")
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
            if(record['name']) {
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
        Cloud cloud = getIntegrationCloud(integration)
        def config = integration.getConfigMap()
        log.info("verifyAccountIntegration - Validating integration: ${integration.name}")
        try {
            // Validate Form options
            rtn.errors = [:]
            // cloud integrations use the cloud settings
            if(!cloud) {
	            if(!integration.name || integration.name == ''){
	                rtn.errors['name'] = 'name is required'
	            }
	            // JD: we used to require this for integration only, should All be available now
	            // if((!integration.serviceUrl || integration.serviceUrl == '')){
	            //     rtn.errors['serviceUrl'] = 'Amazon Region is required'
	            // }
	            if((!integration.serviceUsername || integration.serviceUsername == '') && (!integration.credentialData?.username || integration.credentialData?.username == '')){
	                rtn.errors['serviceUsername'] = 'Access Key is required'
	            }
	            if((!integration.servicePassword || integration.servicePassword == '') && (!integration.credentialData?.password || integration.credentialData?.password == '')){
	                rtn.errors['servicePassword'] = 'Secret Key is required'
	            }
	        }

            // Validate Connectivity to Amazon Route53
            if(rtn.errors.size() == 0) {
                log.debug("verifyAccountIntegration - integration: ${integration.name} - checking access to AWS")
                def testResults = AmazonComputeUtility.testConnection(integration, cloud)
				if(testResults.success) {
					def amazonClient = AmazonComputeUtility.getAmazonRoute53Client(integration, cloud, true)
					log.debug("verifyAccountIntegration - integration: ${integration.name} - checking access to DNS Services")
					def hostedZones = AmazonComputeUtility.listDnsHostedZones(amazonClient)
					if(hostedZones.success) {
						// success
						rtn.success = true
					} else {
						// failed to retrieve list of domains
						log.error("verifyAccountIntegration - integration: ${integration.name} - Cannot access Route 53 with the provided region and credentials")
						String errorMessage = 'Cannot access Route 53 with the provided region and credentials'
						rtn.errors['serviceUrl'] = errorMessage
						rtn.errors['serviceUsername'] = errorMessage
						rtn.errors['servicePassword'] = errorMessage
					}
				} else {
					// failed to retrieve list of regions
					log.error("verifyAccountIntegration - integration: ${integration.name} - Cannot access AWS with the provided region and credentials - Results: ${testResults}")
					String errorMessage = 'Cannot access AWS with the provided region and credentials'
					rtn.errors['serviceUrl'] = errorMessage
					rtn.errors['serviceUsername'] = errorMessage
					rtn.errors['servicePassword'] = errorMessage
				}
            }

            if(rtn.errors.size() > 0) {
                //Report Validation errors
                log.error("verifyAccountIntegration - integration: ${integration.name}. Form validation errors while Adding Integration: ${rtn.errors}")
                rtn.success = false
                return rtn
            }
            log.info("verifyAccountIntegration - Integration: ${integration.name} DNS Services validated OK")
            return ServiceResponse.success("DNS Integration validated OK")

        } catch(e) {
            log.error("validateService error: ${e}", e)
            return ServiceResponse.error(e.message ?: 'unknown error validating dns service')
        }
	}

	Cloud getIntegrationCloud(AccountIntegration integration) {
		if(integration.refType == 'ComputeZone' || integration.refType == 'Cloud') {
			// this could return null too if refId is missing
			return morpheus.cloud.getCloudById(integration.refId).blockingGet();
		} else {
			return null
		}
	}


}
