package com.morpheusdata.aws

import com.amazonaws.services.costexplorer.AWSCostExplorer
import com.amazonaws.services.costexplorer.model.DateInterval
import com.amazonaws.services.costexplorer.model.DimensionValues
import com.amazonaws.services.costexplorer.model.Expression
import com.amazonaws.services.costexplorer.model.GetCostAndUsageRequest
import com.amazonaws.services.costexplorer.model.GetCostForecastRequest
import com.amazonaws.services.costexplorer.model.GetReservationCoverageRequest
import com.amazonaws.services.costexplorer.model.GetReservationPurchaseRecommendationRequest
import com.amazonaws.services.costexplorer.model.GetReservationUtilizationRequest
import com.amazonaws.services.costexplorer.model.GetSavingsPlansPurchaseRecommendationRequest
import com.amazonaws.services.costexplorer.model.GroupDefinition
import com.amazonaws.services.costexplorer.model.LimitExceededException
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.DescribeReservedInstancesRequest
import com.amazonaws.services.ec2.model.Filter
import com.bertramlabs.plugins.karman.CloudFile
import com.bertramlabs.plugins.karman.StorageProvider
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.AbstractCloudCostingProvider
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.core.util.InvoiceUtility
import com.morpheusdata.model.Account
import com.morpheusdata.model.AccountInvoice
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.NetworkLoadBalancer
import com.morpheusdata.model.OperationData
import com.morpheusdata.model.StorageBucket
import com.morpheusdata.model.StorageServer
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.AccountInvoiceItem
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.reactivex.Observable
import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.CsvRow
import io.reactivex.ObservableEmitter
import io.reactivex.ObservableOnSubscribe
import io.reactivex.annotations.NonNull
import io.reactivex.schedulers.Schedulers

import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Provides AWS Public cloud costing data import into morpheus on a daily basis. Costing Providers always run around 1AM UTC.
 * This particular one has several modes. The preferred method for grabbing costing data is to import the csv files in an s3 bucket known as the
 * Billing CUR Report. This has been optimized heavily to efficiently import and group that data format.
 *
 * This will also work for one master payer account to sync costing down to all sub accounts discovered in the system.
 *
 * <p>
 *     However, some AWS Users do not have access to this report. An AWS Cost Explorer API is also available but is less capable and is very expensive to call.
 *     If that is available this will get attempted to at least grab a cloud cost summary set of data.
 * </p>
 * <p>
 *     Another aspect of this cost provider is to gather reservation/forecasting information depending on need from the system.
 * </p>
 *
 * @author David Estes
 * TODO: Still a work in progress
 */
@Slf4j
class AWSCloudCostingProvider extends AbstractCloudCostingProvider {
	protected MorpheusContext morpheusContext
	protected Plugin plugin
	static String interval='month'
	static Map<String,Object> compatibleReportConfig = [timeUnit:'HOURLY', format:'textORcsv', compresssion:'GZIP', extraElements:new ArrayList<String>(['RESOURCES']),
														refreshClosed:true, versioning:'CREATE_NEW_REPORT']
	static String billingDateFormat = 'yyyyMMdd'
	static String billingPeriodFormat = "yyyyMMdd'T'HHmmss.SSS'Z'"
	static Integer billingBatchSize = 100
	static String dateIntervalFormat = 'yyyy-MM-dd'
	static String defaultReportName = 'morpheus-costing'

	AWSCloudCostingProvider(AWSPlugin plugin, MorpheusContext morpheusContext) {
		this.plugin = plugin
		this.morpheusContext = morpheusContext
	}

	@Override
	MorpheusContext getMorpheus() {
		return this.morpheusContext
	}

	@Override
	Plugin getPlugin() {
		return this.plugin
	}

	@Override
	String getCode() {
		return "aws-cloud-costing"
	}

	@Override
	String getName() {
		return "AWS Cloud Costing"
	}


	/**
	 * This is where the magic starts!
	 * @param cloud
	 * @param costDate
	 * @param cloudRefreshOptions
	 */
	@Override
	void refreshDailyZoneCosting(Cloud cloud, Date costDate, CloudRefreshOptions cloudRefreshOptions) {
		Date syncDate = new Date()
		try {
			morpheus.async.cloud.updateCloudCostStatus(cloud,Cloud.Status.syncing,null,syncDate)

			if(initializeCosting(cloud).success) {
				if (cloud && cloud.costingMode in ['full', 'costing']) {
					//zone reservations
					try {
						def costingReportDef = loadAwsBillingReportDefinition(cloud)
						def costingReport = costingReportDef?.reportName ?: cloud.getConfigProperty('costingReport')
						def costingBucket = costingReportDef?.s3Bucket ?: cloud.getConfigProperty('costingBucket')
						def costingRegion = costingReportDef?.s3Region ?: cloud.getConfigProperty('costingRegion')

						if (!costingBucket && !costingRegion && !costingReport) {
							log.info("No costing report specified. Running Cost Explorer Set")
							refreshCloudCostFromCostExplorer(cloud, costDate)
						}
					} catch (e2) {
						log.error("Error Checking cost explorer data {}", e2.message, e2)
					}
					if (cloud.costingMode == 'full' || cloud.costingMode == 'reservations') {
						//refreshes reservation recommendation information or reservation lists
						refreshCloudReservations(cloud, costDate)
					}

					//detailed billing report
					Calendar cal = Calendar.getInstance()
					int dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
					if (cloudRefreshOptions.noLag != true && dayOfMonth < 4) { //lets check historical
						processAwsBillingReport(cloud, new Date() - 4)
					}
					def results = processAwsBillingReport(cloud, costDate)
					if (results.success) {
						morpheus.async.cloud.updateCloudCostStatus(cloud, Cloud.Status.ok, null, syncDate)
					} else {
						morpheus.async.cloud.updateCloudCostStatus(cloud, Cloud.Status.error, results.msg as String, syncDate)
					}
				} else {
					morpheus.async.cloud.updateCloudCostStatus(cloud, Cloud.Status.ok, null, syncDate)
				}
				log.info("done refreshing daily resource invoice")
			}
		} catch (e) {
			log.error("refreshDailyResourceInvoice error: {}",e.message, e)
			morpheus.async.cloud.updateCloudCostStatus(cloud,Cloud.Status.error,e.message,syncDate)
		}
	}

	def processAwsBillingReport(Cloud cloud, Date costDate) {
		def rtn = [success:false]
		costDate = costDate ?: new Date()
		String lock = null
		Date startDate = DateUtility.getStartOfGmtMonth(costDate)
		Date endDate = DateUtility.getStartOfNextGmtMonth(costDate)
		String dateFolder = DateUtility.formatDate(startDate, billingDateFormat) + '-' + DateUtility.formatDate(endDate, billingDateFormat)
		String period = InvoiceUtility.getPeriodString(startDate)
		String lockZoneIdentifier = cloud.externalId ?: cloud.id
		String lockName = "aws.billing.process.${lockZoneIdentifier}.${period}"

		try {
			//connect to bucket
			// use report definition if available
			def costingReportDef = loadAwsBillingReportDefinition(cloud)
			def costingReport = costingReportDef?.reportName ?: cloud.getConfigProperty('costingReport')
			String costingBucket = costingReportDef?.s3Bucket as String ?: cloud.getConfigProperty('costingBucket') as String
			String costingRegion = costingReportDef?.s3Region as String ?: cloud.getConfigProperty('costingRegion') as String
			String costingFolder = costingReportDef?.s3Prefix as String ?: cloud.getConfigProperty('costingFolder') as String ?: ''

			if(costingBucket && costingRegion && costingReport) {
				def accessKey = AmazonComputeUtility.getAmazonCostingAccessKey(cloud)
				def secretKey = AmazonComputeUtility.getAmazonCostingSecretKey(cloud)
				def stsAssumeRole = null
				def useHostCredentials = null
				if(accessKey == AmazonComputeUtility.getAmazonAccessKey(cloud)) {
					//no costing account alternative credentials being used
					stsAssumeRole = AmazonComputeUtility.getAmazonStsAssumeRole(cloud)
					useHostCredentials = AmazonComputeUtility.getAmazonUseHostCredentials(cloud)
				}

				def providerConfig = [provider:'s3', accessKey:accessKey, secretKey:secretKey, region:costingRegion, useHostCredentials: useHostCredentials, stsAssumeRole: stsAssumeRole]
				if(cloud?.apiProxy) {
					providerConfig.proxyHost = cloud.apiProxy.proxyHost
					providerConfig.proxyPort = cloud.apiProxy.proxyPort
					if(cloud.apiProxy.proxyUser) {
						providerConfig.proxyUser = cloud.apiProxy.proxyUser
						providerConfig.proxyPassword = cloud.apiProxy.proxyPassword

					}
					providerConfig.proxyDomain = cloud.apiProxy.proxyDomain
					providerConfig.proxyWorkstation = cloud.apiProxy.proxyWorkstation
				}
				def provider = StorageProvider.create(providerConfig)

				log.info("dateFolder: {}", dateFolder)
				def reportRoot = provider[costingBucket]
				def manifest = reportRoot[costingFolder + '/' + costingReport + '/' + dateFolder + '/' + costingReport + '-Manifest.json']

				if(morpheusContext.checkLock(lockName.toString(), [:]).blockingGet()) {
					log.warn("Lock Already Exists for Processing AWS Billing Period: ${period}...If this is in error, please purge lock from DistributedLock table.")
					return rtn
				}

				lock = morpheusContext.acquireLock(lockName, [timeout: 48l * 60l * 60l * 1000l, ttl: 48l * 60l * 60l * 1000l]).blockingGet()

				def processReport
				if(manifest.exists()) {
					processReport = [manifestFile:manifest,provider:provider,bucket:costingBucket, lastModified: manifest.getDateModified()]
				} else {
					log.warn("Manifest File not found: ${costingFolder + '/' + costingReport + '/' + dateFolder + '/' + costingReport + '-Manifest.json'}")
					morpheusContext.async.cloud.updateCloudCostStatus(cloud,Cloud.Status.error,"Manifest File not found: ${costingFolder + '/' + costingReport + '/' + dateFolder + '/' + costingReport + '-Manifest.json'}",new Date())
				}
				if(processReport) {
					processAwsBillingFiles(cloud, processReport, costDate, [lineCounter:0, lineItems:0, newInvoice:0, newItem:0, newResource:0, itemUpdate:0, unprocessed:0, lastEndDate:null, lockName: lockName])
				}
				rtn.success = true
				//done
			}
		} catch(e) {
			rtn.msg = e.message
			log.error("error processing billing: ${e}", e)
		} finally {
			if(lock) {
				morpheusContext.releaseLock(lockName,[lock:lock]).blockingGet()
			}
		}
		return rtn
	}


//	@CompileStatic
	void processAwsBillingFiles(Cloud cloud, Map costReport, Date costDate, Map opts) {

		try {
			//download the manifest
			Date startDate = new Date()
			String manifestJson = (costReport.manifestFile as CloudFile).getText()
			Map manifest = new JsonSlurper().parseText(manifestJson) as Map
			log.debug("manifest: {}", manifest)
			List<String> reportKeys = manifest.reportKeys as List<String>
			Map<String, Object> billingPeriod = manifest.billingPeriod as Map<String, Object>
			Date billingStart = new Date(DateUtility.getGmtDate(DateUtility.parseDateWithFormat(billingPeriod?.start as String, billingPeriodFormat)).getTime() + (24l*60l*60000l))
			Date billingEnd = DateUtility.getGmtDate(DateUtility.parseDateWithFormat(billingPeriod?.end as String, billingPeriodFormat))
			String period = InvoiceUtility.getPeriodString(billingStart)
			opts.usageZones = []
			StorageProvider storageProvider = ((StorageProvider) (costReport.provider))
			log.info("Processing Files for Billing Period {} - File Count: {}", period, reportKeys.size())
			Integer filePosition = 0
			Observable<String> reportObservable = Observable.fromIterable(reportKeys)
			reportObservable.concatMap { String reportKey ->
				return createCsvStreamer(storageProvider,costReport.bucket as String,reportKey,period,manifest)
			}.buffer(billingBatchSize).map {rows ->
				processAwsBillingBatch(cloud,period,rows,costDate,opts)
			}.buffer(billingBatchSize * 10000).concatMapCompletable {
				morpheusContext.async.costing.invoice.bulkReconcileInvoices([])//.andThen(morpheusContext.async.costing.invoice.summarizeCloudInvoice()).andThen(morpheusContext.async.costing.invoice.processProjectedCosts())
			}.blockingAwait()
		} catch(ex) {
			log.error("Error processing aws billing report file {}",ex.message,ex)
		}
	}

	InvoiceProcessResult processAwsBillingBatch(Cloud cloud, String tmpPeriod, Collection<Map<String,Object>> lineItems, Date costDate, Map opts) {
		InvoiceProcessResult chunkedResult = new InvoiceProcessResult()
		try {
			Date startDate = DateUtility.getStartOfGmtMonth(costDate)
			List<String> lineItemIds = [] as List<String>
			List<String> usageAccountIds = [] as List<String>
			List<AccountInvoiceItem> invoiceItems = [] as List<AccountInvoiceItem>
			for(li in lineItems) {
				usageAccountIds.add(li.lineItem['UsageAccountId'] as String)
				lineItemIds.add(li.uniqueId as String)
			}
			//reduce size of list for repeat entries for the same item
			usageAccountIds.unique()
			lineItemIds = lineItemIds.unique()
			//grab all clouds that need items generated by this report
			List<Cloud> usageClouds = []
			if(usageAccountIds) {
				def queryFilters = []
				queryFilters.add(new DataOrFilter(
						new DataFilter("externalId","in",usageAccountIds),
						new DataFilter("linkedAccountId","in",usageAccountIds)
				))
				if(!cloud.owner.masterAccount) {
					queryFilters.add(new DataFilter("owner.id",cloud.owner.id))
				}
				usageClouds = morpheusContext.async.cloud.list(new DataQuery().withFilters(queryFilters)).toList().blockingGet()
			}
			Map<String,List<Cloud>> usageCloudsByExternalId = (usageClouds.groupBy{it.linkedAccountId ?: it.externalId} as Map<String,List<Cloud>>)
			List<Long> usageCloudIds = usageClouds ? (List<Long>)(usageClouds.collect{ it.id }) : [] as List<Long>
			usageCloudIds.unique()


			if(lineItemIds) {
				//grab all invoice items we are going to need that may already exist
				invoiceItems = morpheusContext.async.costing.invoiceItem.list(new DataQuery().withFilters(
						new DataFilter("uniqueId","in",lineItemIds),
						new DataFilter("invoice.zoneId","in",usageCloudIds),
						new DataFilter("invoice.period",tmpPeriod),
						new DataFilter("invoice.interval",interval)
				)).toList().blockingGet()
				def invoiceItemsByUniqueId = invoiceItems.groupBy{it.uniqueId}
				def lineItemsToUse = []
				def invoiceItemsToUse = []

				//Filter out line items and invoice items that have already been previously processed to improve
				//performance
				for(li2 in lineItems) {
					Boolean localFound = false
					if(!li2.product['region']) {
						li2.product['region'] = 'global' //alias no region costs to global for now
					}
					String rowId = li2.uniqueId
					BigDecimal itemRate = parseStringBigDecimal(li2.lineItem['UnblendedRate'])
					def invoiceItemsMatched = invoiceItemsByUniqueId[rowId]
					def clouds = usageCloudsByExternalId[li2.lineItem['UsageAccountId']]?.findAll{zn -> (!zn.regionCode || (AmazonComputeUtility.getAmazonEndpointRegion(zn.regionCode) == li2.product['region'] ) || (!li2.product['region'] && zn.regionCode == 'global'))}
					if(!li2.product['region'] || !invoiceItemsMatched) { //global we need to process it
						lineItemsToUse << li2
						if(invoiceItemsMatched) {
							invoiceItemsToUse += invoiceItemsMatched
						}
					} else if(invoiceItemsMatched?.any { it -> it.itemRate != itemRate || !InvoiceUtility.checkDateCheckHash(startDate,li2.lineItem['UsageEndDate'] instanceof String ? DateUtility.parseDate(li2.lineItem['UsageEndDate'] as String) : li2.lineItem['UsageEndDate'] as Date ,it.dateCheckHash)}) {
						lineItemsToUse << li2
						invoiceItemsToUse += invoiceItemsMatched
					} else if(clouds && clouds.collect{it.id}.intersect(invoiceItemsMatched?.collect{it[3]}?.unique() ?: []).size() != clouds.size()) {
						lineItemsToUse << li2
						invoiceItemsToUse += invoiceItemsMatched
					}
				}
				invoiceItems = invoiceItemsToUse
				lineItems = lineItemsToUse
				List<String> resourceIds = [] as List<String>
				for(row in lineItems) {
					def usageAccountId = row.lineItem['UsageAccountId']

					def targetZones = usageCloudsByExternalId[usageAccountId]?.findAll{zn -> !zn.regionCode || (AmazonComputeUtility.getAmazonEndpointRegion(zn.regionCode) == row.product['region'])}
					for(zn in targetZones) {
						def tmpInvoiceItems = invoiceItems[zn.id] ?: [:]
						def itemMatch = tmpInvoiceItems[row.uniqueId]
						if(!itemMatch) {
							resourceIds.add(row.lineItem['ResourceId'] as String)
							break
						}
					}
				}

				def serverListRecords =[]
				//We filter out only ids related to compute server record types in amazon. This could change in future for RDS
				//TODO: Factor in RDS IDS
				def serverResourceIds = []
				def volumeResourceIds = []
				def snapshotResourceIds = []
				def loadBalancerResourceIds = []
				def virtualImageResourceIds = []
				def resourceListRecords
				def existingEmptyInvoices = []
				def resourceStartTime = new Date().time
				if(resourceIds) {
					resourceListRecords = morpheusContext.async.cloud.resource.list(new DataQuery().withFilters(
							new DataFilter("zoneId","in",usageCloudIds),
							new DataFilter("externalId","in",resourceIds)
					)).toList().blockingGet()

					for(int rcount =0; rcount < resourceIds.size() ; rcount++) {
						def resourceId = resourceIds[rcount] as String
						if(resourceId?.startsWith('i-')) {
							serverResourceIds << resourceId
						} else if(resourceId.startsWith('vol-')) {
							volumeResourceIds << resourceId
						} else if(resourceId.startsWith('ami-')) {
							virtualImageResourceIds << resourceId
						} else if(resourceId.startsWith(':loadbalancer/')) {
							loadBalancerResourceIds << resourceId
						} else if(resourceId.startsWith(':snapshot/')) {
							snapshotResourceIds << resourceId
						}
					}
				}
				def serverList = []
				if(serverResourceIds) {
					//we need to look for any possible matching server records
					serverList = morpheusContext.async.computeServer.list(new DataQuery().withFilters(
							new DataFilter("zone.id","in",usageCloudIds),
							new DataFilter("externalId","in",serverResourceIds)
					)).toList().blockingGet()
					if(volumeResourceIds) {
						serverList += morpheusContext.async.computeServer.list(new DataQuery().withFilters(
								new DataFilter("zone.id","in",usageCloudIds),
								new DataFilter("volumes.externalId","in",volumeResourceIds)
						)).toList().blockingGet()
					}
					serverList.unique{it.id}

				}
				if(volumeResourceIds) {
					//load storage volume objects
//					morpheusContext.
				}

				//TODO: Load all resource objects we may need

				accountIds = accountIds.unique()
				Map<Long, Account> resourceAccounts
				if(accountIds) {
					resourceAccounts = Account.where{id in accountIds}.list()?.collectEntries{ [(it.id):it] } as Map<Long, Account>
				}

				List<AccountInvoice> invoiceUpdates = [] as List<AccountInvoice>
				List<AccountInvoiceItem> invoiceItemUpdates = [] as List<AccountInvoiceItem>
				Map<String,List<Map<String,Object>>> usageAccountLineItems = (lineItems.groupBy{ r->r.lineItem['UsageAccountId']} as Map<String,List<Map<String,Object>>>)

				// log.info("Prep Time: ${new Date().time - checkTime.time}")
				checkTime = new Date()
				for(usageAccountId in usageAccountLineItems.keySet()) {
					List<Cloud> targetClouds = usageCloudsByExternalId[usageAccountId]
					for(Cloud targetCloud in targetClouds) {
						Map<String,AccountInvoice> invoiceList = invoiceListByZone[targetCloud.id] ?: [:] as Map<String,AccountInvoice>
						Map<String,AccountInvoiceItem> invoiceItemsForCloud = invoiceItemsByZone[targetCloud.id] ?: [:] as Map<String, AccountInvoiceItem>
						def resourceList = resourceListByZone[targetCloud.id] ?: [:]
						def serverScopedList = serverListByZone[targetCloud.id] ?: [:]
						def volumeList = volumeListByZone[targetCloud.id] ?: [:]
						def volumeServerMatches = volumeServerMatchesByZone[targetCloud.id] ?: [:]

						def emptyInvoicesList = emptyInvoicesByZone[targetCloud.id] ?: [:]
						def lbList = lbListByZone[targetCloud.id] ?: [:]
						Map<Long,AccountInvoice> lbInvoices = lbInvoiceRecordsByZone[targetCloud.id] ?: [:]
						Map<Long,AccountInvoice> serverInvoices = serverInvoiceRecordsByZone[targetCloud.id] ?: [:]
						Map<Long,AccountInvoice> volumeInvoices = volumeInvoiceRecordsByZone[targetCloud.id] ?: [:]
						//process line items
						Date filterTime = new Date()
						LinkedList<Map<String,Object>> regionLineItems = (usageAccountLineItems[usageAccountId].findAll{ tmpRow -> (tmpRow.product['region'] && (!targetCloud.regionCode || (AmazonComputeService.getAmazonEndpoingRegion(targetCloud.regionCode) == tmpRow.product['region']) || (!tmpRow.product['region'] && targetCloud.regionCode == 'global')))} as LinkedList<Map<String,Object>>)

						for(Map<String,Object> row in regionLineItems) {
							Boolean newResourceMade = false
							String rowId = row.uniqueId
							def resourceId = row.lineItem['ResourceId']
							def lineItemId = row.identity['LineItemId']
							Date lineItemStartDate = row.lineItem['UsageStartDate'] instanceof String ? DateUtility.parseDate(row.lineItem['UsageStartDate'] as String) : row.lineItem['UsageStartDate'] as Date
							Date lineItemEndDate = row.lineItem['UsageEndDate'] instanceof String ? DateUtility.parseDate(row.lineItem['UsageEndDate'] as String) : row.lineItem['UsageEndDate'] as Date
							def volMatch = null
							def tags = row.resourceTags?.collect { [name: it.key.startsWith('user:') ? it.key.substring(5) : it.key, value: it.value]}?.findAll{it.value}
							//find the line item
							def itemMatch = invoiceItemsForCloud[rowId]
							//if item match - just update info
							if(itemMatch) {
								Boolean recalculationTotalsNecessary=false
								BigDecimal itemRate = parseStringBigDecimal(row.lineItem['UnblendedRate']) ?: 0.0G
								if(!InvoiceUtility.checkDateCheckHash(startDate,lineItemEndDate,itemMatch.dateCheckHash)) {
									//do an update
									def itemConfig = [
											startDate:lineItemStartDate, endDate:lineItemEndDate, itemUsage:parseStringBigDecimal(row.lineItem['UsageAmount']),
											itemRate:itemRate, itemCost:parseStringBigDecimal(row.lineItem['UnblendedCost']),
											onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? parseStringBigDecimal(row.pricing['publicOnDemandCost']) : 0.0,
											amortizedCost:getAmortizedCost(row),
											zoneRegion: targetCloud.regionCode,
											rateExternalId:row.savingsPlan['SavingsPlanARN'] ?: row.reservation['ReservationARN'],
											costProject:row.costCategory ? row.costCategory['Project'] : null,
											costTeam:row.costCategory ? row.costCategory['Team'] : null,
											costEnvironment:row.costCategory ? row.costCategory['Environment'] : null,
											availabilityZone:row.lineItem['AvailabilityZone'],
											operatingSystem:row.product ? row.product['operatingSystem'] : null,
											purchaseOption:row.savingsPlan['SavingsPlanARN'] ? 'Savings Plan' : row.reservation['ReservationARN'] ? 'Reserved' : row.lineItem['UsageType']?.contains('Spot') ? 'Spot' : 'On Demand',
											tenancy:row.product ? row.product['tenancy'] : null,
											databaseEngine:row.product ? row.product['databaseEngine'] : null,
											billingEntity:row.bill['BillingEntity'],
											itemTerm:row.pricing['term'], regionCode: row.product['region'],lastInvoiceSyncDate: costDate
									]
									Map itemChanged = accountInvoiceService.updateAccountInvoiceItem(itemMatch, itemConfig, null,false,false)
									if(itemChanged.changed == true) {
										itemMatch.dateCheckHash = InvoiceUtility.updateDateCheckHash(startDate,lineItemEndDate,itemMatch.dateCheckHash)
										invoiceItemUpdates << itemMatch
										chunkedResult.invoicesToReconcile.add(itemMatch.invoice.id)
									}
								}
							} else {
								//find an invoice match
								AccountInvoice invoiceMatch = invoiceList[resourceId]
								def resourceMatch
								def serverMatch = null
								if(invoiceMatch) {
									resourceMatch = resourceList[resourceId]
									//look by server or what not
								} else if(emptyInvoicesList[resourceId]) {
									invoiceMatch = emptyInvoicesList[resourceId]
								} else {
									//find a resource
									resourceMatch = resourceList[resourceId]
									//if no match. - check for server
									if(!resourceMatch) {
										//check for a server, instance, container
										serverMatch = serverScopedList[resourceId]
										if(serverMatch) {
											//log.debug("querying for resource")
											//create a resource for the server?
											invoiceMatch = serverInvoices[serverMatch[0]]
											// invoiceMatch = AccountInvoice.where{ refType == 'ComputeServer' && refId == resourceMatch.id && period == period && interval == 'month' }.get()
										}
										//check for volume - add it to its server
										if(!serverMatch) {
											resourceMatch = volumeList[resourceId]
											if(resourceMatch) {
												//get what its attached to?
												//create a resource for the volume?
												volMatch = resourceMatch
												serverMatch = volumeServerMatches ? volumeServerMatches[resourceMatch.id] : null
												if(serverMatch) {
													invoiceMatch = serverInvoices[serverMatch[0]]
												} else {
													invoiceMatch = volumeInvoices[resourceMatch.id]
												}
											} else {
												//lb lookup
												resourceMatch = lbList[resourceId]
												if(resourceMatch) {
													invoiceMatch = lbInvoices(resourceMatch.id)
												}
											}
										}
										//add instance / container lookups?
									}
									if(!resourceMatch && !serverMatch ) {
										// println("no resource match...creating item ${lineItemId} ${resourceId}")
										//create one if we have no match
										def scriptConfig = [:]
										// log.info("Creating Resource")
										if(resourceId) {
											def resourceResults = amazonResourceMappingService.createResource(targetCloud, row, opts,false)
											if(resourceResults.success == true && resourceResults.data.resource) {
												// println("resourceResults.data.resource: ${resourceResults.data.resource}")
												resourceMatch = resourceResults.data.resource
												resourceList[resourceId] = resourceMatch
												newResourceMade = true
												batchStats.newResource++
											} else {
												//can assign the item to the overall zone invoice
												invoiceMatch = AccountInvoice.where{ refType == 'Cloud' && refId == targetCloud.id && period == period && interval == 'month' }.get()
												resourceMatch = targetCloud
											}
										} else {
											invoiceMatch = AccountInvoice.where{ refType == 'Cloud' && refId == targetCloud.id && period == period && interval == 'month' }.get()
											resourceMatch = targetCloud
										}

									}
									//if still no invoice - create one
									if(!invoiceMatch) {
										// log.info("No invoice match for ${rowId}")
										if(resourceMatch || serverMatch) {
											def resourceType
											Boolean appendToServerInvoices = false
											def invoiceConfig = [estimate:false, startDate:startDate, refCategory:'invoice', resourceExternalId: resourceId]
											if(resourceMatch instanceof AccountResource) {
												invoiceConfig = configureResourceInvoice(targetCloud, (AccountResource)resourceMatch, invoiceConfig)
												resourceType = 'AccountResource'
											}
											else if(resourceMatch instanceof ComputeServer) {
												invoiceConfig = configureResourceInvoice(targetCloud, (ComputeServer)resourceMatch, invoiceConfig)
												resourceType = 'ComputeServer'
												appendToServerInvoices = true
											}
											else if(resourceMatch instanceof Cloud) {
												invoiceConfig = configureResourceInvoice(targetCloud, invoiceConfig)
												resourceType = 'Cloud'
											} else if(resourceMatch instanceof NetworkLoadBalancer) {
												invoiceConfig = configureResourceInvoice(targetCloud, (NetworkLoadBalancer)resourceMatch, invoiceConfig)
												resourceType = 'NetworkLoadBalancer'
											} else if(serverMatch) {
												invoiceConfig = [refName:serverMatch[1], userId:serverMatch[2], zoneId:serverMatch[3], zoneUUID: serverMatch[12], zoneName:serverMatch[4],
																 siteId:serverMatch[5], planId:serverMatch[6], userName:serverMatch[7], planName:serverMatch[8],
																 layoutId:serverMatch[9], layoutName:serverMatch[10], serverId:serverMatch[0], serverName:serverMatch[1],
																 resourceExternalId:serverMatch[11], refUUID: serverMatch[13], zoneRegion: serverMatch[14], account: resourceAccounts[serverMatch[15]]
												]
												appendToServerInvoices = true
												resourceType = 'ComputeServer'
											} else if(resourceMatch instanceof StorageVolume) {
												invoiceConfig = configureResourceInvoice(targetCloud, (StorageVolume)resourceMatch, invoiceConfig)
												resourceType = 'StorageVolume'
											}

											//create it
											invoiceConfig.lastInvoiceSyncDate = costDate
											invoiceConfig.tags = tags

											def invoiceResults = accountInvoiceService.ensureActiveAccountInvoice(targetCloud.owner, invoiceConfig.account ?: targetCloud.account, resourceType,
													resourceMatch?.id ?: invoiceConfig.serverId, interval, costDate, invoiceConfig, resourceMatch?.uuid ?: invoiceConfig.refUUID, newResourceMade)
											if(invoiceResults.invoice) {
												invoiceMatch = invoiceResults.invoice
												invoiceMatch.save()
												invoiceList[resourceId] = invoiceMatch
												if(resourceMatch instanceof StorageVolume) {
													volumeInvoices[invoiceMatch.refId] = invoiceMatch
												}
												if(resourceMatch instanceof StorageVolume) {
													volumeInvoices[invoiceMatch.refId] = invoiceMatch
												}
												if(serverMatch) {
													serverInvoices[invoiceMatch.refId] = invoiceMatch
												}
												batchStats.newInvoice++
											} else {
												log.info("Warning...Unprocessed Item ${lineItemId} - ${resourceId}")
												batchStats.unprocessed++
											}
										} else {
											log.warn("Unprocessed things")
											//shouldn't get here ever
											batchStats.unprocessed++
										}
									}
								}
								//if we have an invoice
								if(invoiceMatch) {
									def category = categoryForInvoiceItem(row.product['servicecode'],row.lineItem['UsageType'])
									//create the line item
									BigDecimal unblendedRate = parseStringBigDecimal(row.lineItem['UnblendedRate'])
									BigDecimal unblendedCost = parseStringBigDecimal(row.lineItem['UnblendedCost'])
									def itemConfig = [
											refName:invoiceMatch.refName, refCategory:'invoice',
											startDate:lineItemStartDate, endDate:lineItemEndDate, itemId:lineItemId, itemType:row.lineItem['LineItemType'],
											itemName:invoiceMatch.refName, itemDescription:row.lineItem['LineItemDescription'],
											zoneRegion: targetCloud.regionCode,
											productCode:row.lineItem['ProductCode'], productName:row.lineItem['ProductName'],
											itemSeller:row.lineItem['LegalEntity'], itemAction:row.lineItem['Operation'], usageType:row.lineItem['UsageType'],
											usageService:row.product['servicecode'], rateId:row.pricing['RateId'], rateClass:row.lineItem['RateClass'],
											rateUnit:row.pricing['unit'], rateTerm:row.pricing['LeaseContractLength'], itemUsage:parseStringBigDecimal(row.lineItem['UsageAmount']),
											itemRate:unblendedRate, itemCost:unblendedCost,
											onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? parseStringBigDecimal(row.pricing['publicOnDemandCost']) : 0.0,
											amortizedCost: getAmortizedCost(row),
											rateExternalId:row.savingsPlan['SavingsPlanARN'] ?: row.reservation['ReservationARN'],
											costProject:row.costCategory ? row.costCategory['Project'] : null,
											costTeam:row.costCategory ? row.costCategory['Team'] : null,
											costEnvironment:row.costCategory ? row.costCategory['Environment'] : null,
											availabilityZone:row.lineItem['AvailabilityZone'],
											operatingSystem:row.product ? row.product['operatingSystem'] : null,
											purchaseOption:row.savingsPlan['SavingsPlanARN'] ? 'Savings Plan' : row.reservation['ReservationARN'] ? 'Reserved' : row.lineItem['UsageType']?.contains('Spot') ? 'Spot' : 'On Demand',
											tenancy:row.product['tenancy'],
											databaseEngine:row.product['databaseEngine'],
											billingEntity:row.bill ? row.bill['BillingEntity'] : null,
											itemTerm:row.pricing['term'], taxType:row.lineItem['TaxType'], usageCategory: category, uniqueId: rowId,
											regionCode: row.product['region'], lastInvoiceSyncDate: costDate
									]
									//itemPrice:, itemTax:, productId:row.lineItem[''], usageCategory:,
									def itemResults
									if(volMatch) {
										itemResults = accountInvoiceService.createAccountInvoiceItem(invoiceMatch, 'StorageVolume', volMatch.id, lineItemId, itemConfig, false)
									} else {
										itemResults = accountInvoiceService.createAccountInvoiceItem(invoiceMatch, invoiceMatch.refType, invoiceMatch.refId, lineItemId, itemConfig, false)
									}

									itemResults.invoiceItem.dateCheckHash = InvoiceUtility.updateDateCheckHash(startDate,lineItemEndDate,itemResults.invoiceItem.dateCheckHash)
									invoiceItemsForCloud[itemConfig.uniqueId] = itemResults.invoiceItem
									invoiceItemUpdates << itemResults.invoiceItem
									chunkedResult.invoicesToReconcile << invoiceMatch.id
									//done
								}
							}
						}
					}
				}

				// log.info("Process Time: ${new Date().time - checkTime.time}")
				//invoice updates
				if(invoiceItemUpdates) {
					morpheusContext.async.costing.invoiceItem.bulkSave(invoiceItemUpdates).blockingGet()
				}
				if(invoiceUpdates) {
					invoiceUpdates?.each { invoiceUpdate ->
						invoiceUpdate.lastInvoiceSyncDate = costDate
					}
					morpheusContext.async.costing.invoice.bulkSave(invoiceUpdates).blockingGet()
				}
			}

			chunkedResult.usageZones.addAll(usageClouds.collect{it.uuid})
			return chunkedResult
		}catch(e) {
			log.error("Error Occurred Processing Batched set of Line Items {}",e.message,e)
		}
	}

	Observable<Map> createCsvStreamer(StorageProvider storageProvider, String bucket, String reportKey, String period, Map manifest) {
		Observable<Map> fileProcessor = Observable.create(new ObservableOnSubscribe<Map>() {
			@Override
			public void subscribe(@NonNull ObservableEmitter<Map> emitter) throws Exception {
				File costFile
				InputStream dataFileStream
				try {
					log.info("Processing File ${reportKey} for Billing Period {}", period)
					CloudFile dataFile = storageProvider[bucket as String][reportKey]

					if(!dataFile.exists()) {
						log.warn("Missing Amazon Costing Report File Defined in Manifest, Continuing to next file")
						throw new Exception("Missing Amazon Costing Report File Defined in Manifest, Continuing to next file")
					}


					//processing stats
					Map batchStats = [batchCount: 0L, lineItems: 0L, newInvoice: 0L, newItem: 0L, newResource: 0L, itemUpdate: 0L, unprocessed: 0L, maxDate: null]
					//period date
					Integer retryCount = 0
					//need to throw an exception to break out
					Boolean pending = true
					while(pending) {
						try {
							costFile = getTemporaryCostFile()
							OutputStream os = costFile.newOutputStream()
							os << dataFile.getInputStream()
							os.flush()
							os.close()
							break
						} catch(e) {
							log.error("Error Downloading File for AWS Cost CUR Report. Trying again...")
							retryCount++
							sleep(30000l)
							if(retryCount > 3) {
								throw new Exception("Error Processing AWS Billing Report...Failed to download file ${reportKey}", e)
							}
						}
					}
					dataFileStream = new GZIPInputStream(costFile.newInputStream(), 65536)
					BufferedReader br = new BufferedReader(new InputStreamReader(dataFileStream))
					CsvReader csvReader = CsvReader.builder().build(br)
					Long lineCounter = 0
					List columnList = []
					for(CsvRow line : csvReader) {
						if(lineCounter == 0) {
							//process the header
							columnList = processBillingHeader(manifest, line)
							log.debug("columnList: {}", columnList)
						} else {
							Map<String, Object> row = processBillingLine(columnList, line)
							//check if its got a price to it - and is past the last process cutoff
							if(row && row.lineItem && (row.lineItem as Map<String, Object>)['UnblendedCost']) {
								row.uniqueId = getLineItemHash("${row.lineItem['UsageAccountId']}_${row.product['servicecode']}_${row.lineItem['UsageType']}_${row.lineItem['Operation']}_${row.lineItem['LineItemType']}_${row.lineItem['UnblendedRate']}_${row.lineItem['ResourceId']}_${row.savingsPlan['SavingsPlanARN']}_${row.lineItem['AvailabilityZone']}_${row.reservation['SubscriptionId']}_${row.lineItem['LineItemDescription']}")
								emitter.onNext(row)
							}

						}
						lineCounter++
					}
					emitter.onComplete();
				} catch(Exception e) {
					emitter.onError(e);
				}finally {
					if(dataFileStream) {
						try {
							dataFileStream.close()
						} catch(ignore) {
							//ignore close errors
						}
					}
					try {
						if(costFile) {
							costFile.delete()
						}
					} catch(ex3) {
						log.warn("Error Deleting Cost File Cache: ${ex3.message}")
					}

				}
			}
		}).subscribeOn(Schedulers.io())

		return fileProcessor
	}

	InvoiceProcessResult processAwsBillingBatchOld(Cloud cloud, String tmpPeriod, Map costReport, Collection lineItems, Date costDate, Map opts) {
		def batchStats = [success:false, lineItems:lineItems.size(), usageZones: [],invoicesToReconcile: new HashSet<Long>(), newInvoice:0, newItem:0, newResource:0, itemUpdate:0,
						  unprocessed:0, maxDate:null]
		InvoiceProcessResult chunkedResult = new InvoiceProcessResult()

		try {
			String tmpInterval = 'month'
			Date startDate = DateUtility.getStartOfGmtMonth(costDate)
			List<String> lineItemIds = [] as List<String>
			List<String> usageAccountIds = [] as List<String>
			for(li in lineItems) {
				usageAccountIds << li.lineItem['UsageAccountId'] as String

				lineItemIds << li.uniqueId
			}
			usageAccountIds.unique()
			lineItemIds = lineItemIds.unique()
			List<String> statsUsageZones = batchStats.usageZones as List<String>
			List<Cloud> usageZones = []
			if(usageAccountIds) {
				def queryFilters = []
				queryFilters.add(new DataOrFilter(
						new DataFilter("externalId","in",usageAccountIds),
						new DataFilter("linkedAccountId","in",usageAccountIds)
				))
				if(!cloud.owner.masterAccount) {
					queryFilters.add(new DataFilter("owner.id",cloud.owner.id))
				}
			}
			usageZones = morpheusContext.async.cloud.list(new DataQuery().withFilters(quaryFilters)).toList().blockingGet()


			statsUsageZones.addAll(usageZones?.collect{it.linkedAccountId ?: it.externalId} as List<String>)
			statsUsageZones.unique()
			Map<String,List<Cloud>> usageZonesByExternalId = (usageZones.groupBy{it.linkedAccountId ?: it.externalId} as Map<String,List<Cloud>>)
			List<Long> usageZoneIds = usageZones ? (List<Long>)(usageZones.collect{ it.id }) : [] as List<Long>
			usageZoneIds.unique()
			if(usageZoneIds) {
				//existing line items for this batch
				def invoiceItems = [:]
				def invoiceRecords
				if(lineItemIds) {
					invoiceRecords = AccountInvoiceItem.withCriteria(cache:false) {
						createAlias('invoice','invoice')
						inList('uniqueId',lineItemIds)
						inList('invoice.zoneId',usageZoneIds)
						eq('invoice.period',tmpPeriod)
						eq('invoice.interval',tmpInterval)


						projections {
							property('id')
							property('uniqueId')
							property('invoice.id')
							property('invoice.zoneId')
							property('invoice.resourceUuid')
							property('itemRate')
							property('dateCheckHash')
						}
					}

					def invoiceItemsByUniqueId = invoiceRecords.groupBy{it[1]}//.collectEntries{ [(it[1]):it]} //TODO THIS IS A PROBLEM WHEN MULTIPLE OF SAME CLOUD IS IN EFFECT
					// log.info("invoiceItemsByUniqueId ${invoiceItemsByUniqueId} ")
					def lineItemsToRemove = []
					def lineItemsToUse = []
					def invoiceItemsToUse = []
					for(li2 in lineItems) {
						Boolean localFound = false
						if(!li2.product['region']) {
							li2.product['region'] = 'global' //alias no region costs to global for now
						}
						String rowId = li2.uniqueId
						BigDecimal itemRate = parseStringBigDecimal(li2.lineItem['UnblendedRate'])
						def invoiceItemsMatched = invoiceItemsByUniqueId[rowId]

						def zones = usageZonesByExternalId[li2.lineItem['UsageAccountId']]?.findAll{zn -> (!zn.regionCode || (AmazonComputeService.getAmazonEndpoingRegion(zn.regionCode) == li2.product['region'] ) || (!li2.product['region'] && zn.regionCode == 'global'))}
						if(!li2.product['region'] || !invoiceItemsMatched) { //global we need to process it 
							lineItemsToUse << li2
							if(invoiceItemsMatched) {
								invoiceItemsToUse += invoiceItemsMatched
							}
						} else if(invoiceItemsMatched?.any { it -> it[5] != itemRate || !checkDateCheckHash(startDate,MorpheusUtils.parseDate(li2.lineItem['UsageEndDate']),it[6])}) {
							lineItemsToUse << li2
							invoiceItemsToUse += invoiceItemsMatched


						} else if(zones && zones.collect{it.id}.intersect(invoiceItemsMatched?.collect{it[3]}?.unique() ?: []).size() != zones.size()) {
							lineItemsToUse << li2
							invoiceItemsToUse += invoiceItemsMatched
						}


					}

					invoiceRecords = invoiceItemsToUse
					lineItems = lineItemsToUse

					def resourceIds = []
					for(row in lineItems) {
						def usageAccountId = row.lineItem['UsageAccountId']

						def targetZones = usageZonesByExternalId[usageAccountId]?.findAll{zn -> !zn.regionCode || (AmazonComputeService.getAmazonEndpoingRegion(zn.regionCode) == row.product['region'])}
						for(zn in targetZones) {
							def tmpInvoiceItems = invoiceItems[zn.id] ?: [:]

							def itemMatch = tmpInvoiceItems[row.uniqueId]
							if(!itemMatch) {
								resourceIds << row.lineItem['ResourceId']
								break
							}
						}
					}

//					def zoneInvoices = []
//					for(invoiceRecord in invoiceRecords) {
//						def zoneInvoices = invoiceItems[invoiceRecord[3]]
//						if(!zoneInvoices) {
//							zoneInvoices = [:]
//							invoiceItems[invoiceRecord[3]] = zoneInvoices
//						}
//						zoneInvoices[invoiceRecord[1]] = invoiceRecord
//					}

					def serverListRecords =[]
					//We filter out only ids related to compute server record types in amazon. This could change in future for RDS
					//TODO: Factor in RDS IDS
					def serverResourceIds = []
					if(resourceIds) {
						// log.info("Resource Ids Need Processing in ${resourceEndTime - resourceStartTime}ms: ${resourceIds}")
						for(int rcount =0; rcount < resourceIds.size() ; rcount++) {
							def resourceId = resourceIds[rcount] as String
							if(resourceId?.startsWith('i-')) {
								serverResourceIds << resourceId
							}
						}
					}



				}


				def invoiceIds = invoiceRecords?.collect{it[2]}?.unique()
				def resourceUuids = invoiceIds ? invoiceRecords?.collect{it[4]}?.unique() : []
				//all resource uuids and ids
				def invoiceItemIds = invoiceRecords ? invoiceRecords.collect {it[0]} : []
				// def resourceUuids = invoiceItems ? invoiceItems.collect{it.value[2]}.unique() : []
				// def resourceIds = lineItems.collect{it.lineItem['ResourceId']}.unique()
				def resourceIds = []
				for(row in lineItems) {
					def usageAccountId = row.lineItem['UsageAccountId']

					def targetZones = usageZonesByExternalId[usageAccountId]?.findAll{zn -> !zn.regionCode || (AmazonComputeService.getAmazonEndpoingRegion(zn.regionCode) == row.product['region'])}
					for(zn in targetZones) {
						def tmpInvoiceItems = invoiceItems[zn.id] ?: [:]

						def itemMatch = tmpInvoiceItems[row.uniqueId]
						if(!itemMatch) {
							resourceIds << row.lineItem['ResourceId']
							break
						}
					}
				}
				// def resourceIds = lineItems.findAll{rw -> invoiceItems[rw.identity['LineItemId']] == null}.collect{ it.lineItem['ResourceId'] }.unique()
				//get the resources related to these line items
				def resourceListRecords
				def existingEmptyInvoices = []
				def resourceStartTime = new Date().time
				//if(resourceUuids && resourceIds) {
				//	resourceListRecords = AccountResource.where{  zoneId in usageZoneIds && (externalId in resourceIds) }.join('type').join('account').list(cache:false)
				if(resourceIds) {
					resourceListRecords = AccountResource.where{ zoneId in usageZoneIds && (externalId in resourceIds) }.join('type').join('account').list(cache:false)
					existingEmptyInvoices = AccountInvoice.withCriteria(cache:false) {
						inList('resourceExternalId',resourceIds)
						inList('zoneId',usageZoneIds)
						eq('period',tmpPeriod)
						eq('interval',tmpInterval)
						isEmpty('lineItems')
						projections {
							property('id')
						}
					}

				}
				def resourceEndTime = new Date().time

				def resourceList = [:]

				for(resourceItem in resourceListRecords) {
					def zoneResource = resourceList[resourceItem.zoneId]
					if(zoneResource == null) {
						zoneResource = [:]
						resourceList[resourceItem.zoneId] = zoneResource
					}
					zoneResource[resourceItem.externalId] = resourceItem
				}
				// println("Resource List: ${resourceList}")
				def serverListRecords =[]
				//We filter out only ids related to compute server record types in amazon. This could change in future for RDS
				//TODO: Factor in RDS IDS
				def serverResourceIds = []
				if(resourceIds) {
					// log.info("Resource Ids Need Processing in ${resourceEndTime - resourceStartTime}ms: ${resourceIds}")
					for(int rcount =0; rcount < resourceIds.size() ; rcount++) {
						def resourceId = resourceIds[rcount]
						if(resourceId?.startsWith('i-')) {
							serverResourceIds << resourceId
						}
					}
				}
				if(serverResourceIds) {
					serverListRecords = ComputeServer.withCriteria(cache:false) {
						createAlias('zone','zone')
						createAlias('plan','plan', CriteriaSpecification.LEFT_JOIN)
						createAlias('layout','layout', CriteriaSpecification.LEFT_JOIN)
						createAlias('createdBy','createdBy', CriteriaSpecification.LEFT_JOIN)
						inList('zone.id',usageZoneIds)
						inList('externalId',resourceIds)
						projections {
							property('id')
							property('name')
							property('createdBy.id')
							property('zone.id')
							property('zone.name')
							property('provisionSiteId')
							property('plan.id')
							property('createdBy.username')
							property('plan.name')
							property('layout.id')
							property('layout.name')
							property('externalId')
							property('zone.uuid')
							property('uuid')
							property('zone.regionCode') //14
							property('account.id') //15
						}
					}
				}
				// ]resourceIds ? ComputeServer.where{ zone in usageZones && externalId in resourceIds }.join('plan').join('layout').join('createdBy').join('zone').list(cache:false) : []
				def serverList = [:]
				for(serverItem in serverListRecords) {
					def zoneResource = serverList[serverItem[3]]
					if(zoneResource == null) {
						zoneResource = [:]
						serverList[serverItem[3]] = zoneResource
					}
					zoneResource[serverItem[11]] = serverItem
				}


				def volumeResourceIds = []
				if(resourceIds) {
					for(int rcount2 =0; rcount2 < resourceIds.size() ; rcount2++) {
						String resourceId = resourceIds[rcount2]
						if(resourceId.startsWith('vol-')) {
							volumeResourceIds << resourceId
						}
					}
				}

				def volumeListRecords = volumeResourceIds ? StorageVolume.where{ zoneId in usageZoneIds && externalId in volumeResourceIds }.join('account').list(cache:false, readOnly:true) : []
				def volumeList = [:]
				for(volItem in volumeListRecords) {
					def zoneResource = volumeList[volItem.zoneId]
					if(zoneResource == null) {
						zoneResource = [:]
						volumeList[volItem.zoneId] = zoneResource
					}
					zoneResource[volItem.externalId] = volItem
				}


				//Loadbalancer List
				def lbResourceIds = []
				if(resourceIds) {
					for(int rcount =0; rcount < resourceIds.size() ; rcount++) {
						def resourceId = resourceIds[rcount]
						if(resourceId.startsWith(':loadbalancer/')) {
							lbResourceIds << resourceId
						}
					}
				}

				def lbListRecords = lbResourceIds ? NetworkLoadBalancer.where{ cloud.id in usageZoneIds && externalId in lbResourceIds }.join('account').list(cache:false, readOnly:true) : []

				def lbList = [:]
				for(lbItem in lbListRecords) {
					def zoneResource = lbList[lbItem.zone.id]
					if(zoneResource == null) {
						zoneResource = [:]
						lbList[lbItem.zone.id] = zoneResource
					}
					zoneResource[lbItem.externalId] = lbItem
				}


				// log.info("Setup Time: ${new Date().time - checkTime.time}")
				if(lineItems) {
					def batchBResults = processAwsBillingBatchItems(cloud, startDate as Date,tmpPeriod,tmpInterval,lineItems,invoiceItemIds,costDate,resourceList,serverList,volumeList, lbList,usageZonesByExternalId,existingEmptyInvoices,batchStats,opts)
					if(batchBResults.invoicesToReconcile) {
						batchStats.invoicesToReconcile = batchBResults.invoicesToReconcile
					}
				}

			} else {
				log.warn("cant get usage zone ids: ${usageZoneIds} ${usageAccountIds}")
			}


		} catch(e) {
			log.error("error processing billing batch: ${e}", e)
			e.printStackTrace()
		}
		return batchStats
	}

	def processAwsBillingBatchItems(Cloud zone, Date startDate, String period, String interval, Collection lineItems, Collection invoiceItemIds,
									Date costDate, Map resourceListByZone, Map serverListByZone, Map volumeListByZone, Map lbListByZone, Map<Long, List<Cloud>> usageZonesByExternalId,List<Long> existingEmptyInvoices,
									Map batchStats, Map opts) {
		Date checkTime = new Date()
		def rtn = [invoicesToReconcile: new HashSet<Long>()]
		List<Long> accountIds = [] as List<Long>
		List<Long> serverIds = [] as List<Long>
		List<Long> lbIds = [] as List<Long>
		List<Long> volumeIds = [] as List<Long>
		def volumeServerMatchesByZone = [:]
		Map<Long,Map<String,AccountInvoice>> emptyInvoicesByZone = [:]
		Map<Long,Map<Long,AccountInvoice>> serverInvoiceRecordsByZone = [:]
		Map<Long,Map<Long,AccountInvoice>> lbInvoiceRecordsByZone = [:]
		Map<Long,Map<Long,AccountInvoice>> volumeInvoiceRecordsByZone = [:]
		def invoiceItemRows = invoiceItemIds ? AccountInvoiceItem.where{ id in invoiceItemIds }.join('invoice').list(cache:false) : []
		Map<Long,Map<String,AccountInvoiceItem>> invoiceItemsByZone = [:]
		Map<Long,Map<String,AccountInvoice>> invoiceListByZone = [:]
		for(AccountInvoiceItem invoiceItemRec in invoiceItemRows) {
			Map<String,AccountInvoiceItem> zoneResource = invoiceItemsByZone[invoiceItemRec.invoice.zoneId]
			if(zoneResource == null) {
				zoneResource = [:] as Map<String,AccountInvoiceItem>
				invoiceItemsByZone[invoiceItemRec.invoice.zoneId] = zoneResource
			}
			zoneResource[invoiceItemRec.uniqueId] = invoiceItemRec
		}
		if(existingEmptyInvoices) {
			log.info("Existing Empty Invoices Found : ${existingEmptyInvoices}")
			Collection<AccountInvoice> existingInvoices = AccountInvoice.where{id in existingEmptyInvoices}.list()
			for(invoiceListRecord in existingInvoices) {
				def zoneResource = emptyInvoicesByZone[invoiceListRecord.zoneId]
				if(zoneResource == null) {
					zoneResource = [:]
					emptyInvoicesByZone[invoiceListRecord.zoneId] = zoneResource
				}
				zoneResource[invoiceListRecord.resourceExternalId] = invoiceListRecord
			}
		}
		if(resourceListByZone) {
			def resourceIds = resourceListByZone.collect{it.value?.collect{rl -> rl.value.id}}?.flatten()
			if(resourceIds) {
				def invoicesByResource = AccountInvoice.where{ refType == 'AccountResource' && refId in resourceIds && period == period && interval == 'month'}.join('metadata').list()
				for(AccountInvoice inv in invoicesByResource) {
					Map<String,AccountInvoice> invoiceZone = invoiceListByZone[inv.zoneId]
					if(invoiceZone == null) {
						invoiceZone = [:] as Map<String,AccountInvoice>
						invoiceListByZone[inv.zoneId] = invoiceZone
					}
					invoiceZone[inv.resourceExternalId] = inv
				}
			}
		}
		//TODO: Find empty server invoices dur
		//get resource id / existing resources for them
		if(serverListByZone) {
			serverListByZone.each { key,value ->
				serverIds += value.collect{it.value[0]}
				accountIds += value.collect{it.value[15] as Long} as List<Long> //get all accountIds
			}
		}
		//lbs
		if(lbListByZone) {
			lbListByZone.each { key,value ->
				lbIds += value.collect{it.id}
				accountIds += value.collect{it.account.id} //get all accountIds
			}
		}
		//volumes
		volumeListByZone?.each { key, value ->
			volumeIds += value.collect{it.value.id}
		}



		Map<String,List<AccountInvoice>> groupedInvoices = [:]
		if(volumeIds && serverIds && lbIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'ComputeServer' && refId in serverIds) || (refType == 'NetworkLoadBalancer' && refId in lbIds) || (refType == 'StorageVolume' && refId in volumeIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(volumeIds && lbIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'NetworkLoadBalancer' && refId in lbIds) || (refType == 'StorageVolume' && refId in volumeIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(volumeIds && serverIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'ComputeServer' && refId in serverIds) || (refType == 'NetworkLoadBalancer' && refId in lbIds) || (refType == 'StorageVolume' && refId in volumeIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(serverIds && lbIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'ComputeServer' && refId in serverIds) || (refType == 'StorageVolume' && refId in volumeIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(serverIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'ComputeServer' && refId in serverIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(lbIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && (refType == 'NetworkLoadBalancer' && refId in lbIds)}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		} else if(volumeIds) {
			groupedInvoices = AccountInvoice.where { period == period && interval == 'month' && ((refType == 'StorageVolume' && refId in volumeIds))}.join('metadata').list(cache:false)?.groupBy{it.refType} as Map<String,List<AccountInvoice>>
		}
		List<AccountInvoice> volInvoices = groupedInvoices['StorageVolume'] ?: []
		List<AccountInvoice> lbInvoiceRecords = groupedInvoices['NetworkLoadBalancer']
		List<AccountInvoice> serverInvoiceRecords = groupedInvoices['ComputeServer']
		if(serverInvoiceRecords) {
			for(serverInvoice in serverInvoiceRecords) {
				Map<Long,AccountInvoice> zoneResource = serverInvoiceRecordsByZone[serverInvoice.zoneId]
				if(zoneResource == null) {
					zoneResource = [:] as Map<Long,AccountInvoice>
					serverInvoiceRecordsByZone[serverInvoice.zoneId] = zoneResource
				}
				zoneResource[serverInvoice.refId] = serverInvoice
			}
		}
		if(lbInvoiceRecords) {
			for(lbInvoice in lbInvoiceRecords) {
				Map<Long,AccountInvoice> zoneResource = lbInvoiceRecordsByZone[lbInvoice.zoneId]
				if(zoneResource == null) {
					zoneResource = [:]
					lbInvoiceRecordsByZone[lbInvoice.zoneId] = zoneResource
				}
				zoneResource[lbInvoice.refId] = lbInvoice
			}
		}

		if(volumeIds) {
			for(volInvoice in volInvoices) {
				Map<Long,AccountInvoice> zoneResource = volumeInvoiceRecordsByZone[volInvoice.zoneId]
				if(zoneResource == null) {
					zoneResource = [:]
					volumeInvoiceRecordsByZone[volInvoice.zoneId] = zoneResource
				}
				zoneResource[volInvoice.refId] = volInvoice
			}
			def volumeServers = ComputeServer.withCriteria {
				createAlias('zone','zone')
				createAlias('plan','plan', CriteriaSpecification.LEFT_JOIN)
				createAlias('layout','layout', CriteriaSpecification.LEFT_JOIN)
				createAlias('createdBy','createdBy', CriteriaSpecification.LEFT_JOIN)
				createAlias('volumes','volumes')
				inList('volumes.id',volumeIds)

				projections {
					property('id')
					property('name')
					property('createdBy.id')
					property('zone.id')
					property('zone.name')
					property('provisionSiteId')
					property('plan.id')
					property('createdBy.username')
					property('plan.name')
					property('layout.id')
					property('layout.name')
					property('externalId')
					property('zone.uuid')
					property('uuid')
					property('zone.regionCode') //14
					property('account.id') //15
					property('volumes.id') //16

				}
			}
			def volumeServersGroupedByZone = volumeServers.groupBy { it[3]}

			volumeServersGroupedByZone.each { volZoneId, volZoneCol ->
				volumeServerMatchesByZone[volZoneId] = volZoneCol.collectEntries{ [(it[16]):it]}
			}


			// volumeServerMatches = volumeServers?.collectEntries{ [(it[16]):it]}
			List<Long> invoiceServerIds = volumeServers?.collect{it[0] as Long} as List<Long>
			accountIds += volumeServers?.collect{it[15] as Long} as List<Long>

			if(invoiceServerIds) {
				List<AccountInvoice> serverVolInvoiceRecords = AccountInvoice.where{ period == period && interval == 'month' && refType == 'ComputeServer' && refId in invoiceServerIds  }.join('metadata').list(cache:false)
				// println("Server vol invoice records: ${serverVolInvoiceRecords}")
				for(AccountInvoice serverVolInvoice in serverVolInvoiceRecords) {
					Map<Long,AccountInvoice> zoneResource = serverInvoiceRecordsByZone[serverVolInvoice.zoneId]
					if(zoneResource == null) {
						zoneResource = [:]
						serverInvoiceRecordsByZone[serverVolInvoice.zoneId] = zoneResource
					}
					zoneResource[serverVolInvoice.refId] = serverVolInvoice
				}
			}
		}

		accountIds = accountIds.unique()
		Map<Long, Account> resourceAccounts
		if(accountIds) {
			resourceAccounts = Account.where{id in accountIds}.list()?.collectEntries{ [(it.id):it] } as Map<Long, Account>
		}

		List<AccountInvoice> invoiceUpdates = [] as List<AccountInvoice>
		List<AccountInvoiceItem> invoiceItemUpdates = [] as List<AccountInvoiceItem>
		Map<String,List<Map<String,Object>>> usageAccountLineItems = (lineItems.groupBy{ r->r.lineItem['UsageAccountId']} as Map<String,List<Map<String,Object>>>)

		// log.info("Prep Time: ${new Date().time - checkTime.time}")
		checkTime = new Date()
		for(usageAccountId in usageAccountLineItems.keySet()) {
			List<Cloud> targetZones = usageZonesByExternalId[usageAccountId]
			for(Cloud targetZone in targetZones) {
				Map<String,AccountInvoice> invoiceList = invoiceListByZone[targetZone.id] ?: [:] as Map<String,AccountInvoice>
				Map<String,AccountInvoiceItem> invoiceItems = invoiceItemsByZone[targetZone.id] ?: [:] as Map<String, AccountInvoiceItem>
				def resourceList = resourceListByZone[targetZone.id] ?: [:]
				def serverList = serverListByZone[targetZone.id] ?: [:]
				def volumeList = volumeListByZone[targetZone.id] ?: [:]
				def volumeServerMatches = volumeServerMatchesByZone[targetZone.id] ?: [:]

				def emptyInvoicesList = emptyInvoicesByZone[targetZone.id] ?: [:]
				def lbList = lbListByZone[targetZone.id] ?: [:]
				Map<Long,AccountInvoice> lbInvoices = lbInvoiceRecordsByZone[targetZone.id] ?: [:]
				Map<Long,AccountInvoice> serverInvoices = serverInvoiceRecordsByZone[targetZone.id] ?: [:]
				Map<Long,AccountInvoice> volumeInvoices = volumeInvoiceRecordsByZone[targetZone.id] ?: [:]
				//process line items
				Date filterTime = new Date()
				LinkedList<Map<String,Object>> regionLineItems = (usageAccountLineItems[usageAccountId].findAll{ tmpRow -> (tmpRow.product['region'] && (!targetZone.regionCode || (AmazonComputeService.getAmazonEndpoingRegion(targetZone.regionCode) == tmpRow.product['region']) || (!tmpRow.product['region'] && targetZone.regionCode == 'global')))} as LinkedList<Map<String,Object>>)

				for(Map<String,Object> row in regionLineItems) {
					Boolean newResourceMade = false
					String rowId = row.uniqueId
					//println("line item: ${row}")
					def resourceId = row.lineItem['ResourceId']
					def lineItemId = row.identity['LineItemId']
					def lineItemStartDate = MorpheusUtils.parseDate(row.lineItem['UsageStartDate'])
					def lineItemEndDate = MorpheusUtils.parseDate(row.lineItem['UsageEndDate'])
					def volMatch = null

					def tags = row.resourceTags?.collect { [name: it.key.startsWith('user:') ? it.key.substring(5) : it.key, value: it.value]}?.findAll{it.value}

					if(lineItemEndDate > batchStats.maxDate) {
						batchStats.maxDate = lineItemEndDate
					}

					//find the line item
					def itemMatch = invoiceItems[rowId]

					//if item match - just update info
					if(itemMatch) {
						Boolean recalculationTotalsNecessary=false
						BigDecimal itemRate = parseStringBigDecimal(row.lineItem['UnblendedRate']) ?: 0.0G


						if(!checkDateCheckHash(startDate,lineItemEndDate,itemMatch.dateCheckHash)) {
							//do an update
							def itemConfig = [
									startDate:lineItemStartDate, endDate:lineItemEndDate, itemUsage:parseStringBigDecimal(row.lineItem['UsageAmount']),
									itemRate:itemRate, itemCost:parseStringBigDecimal(row.lineItem['UnblendedCost']),
									onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? parseStringBigDecimal(row.pricing['publicOnDemandCost']) : 0.0,
									amortizedCost:getAmortizedCost(row),
									zoneRegion: targetZone.regionCode,
									rateExternalId:row.savingsPlan['SavingsPlanARN'] ?: row.reservation['ReservationARN'],
									costProject:row.costCategory ? row.costCategory['Project'] : null,
									costTeam:row.costCategory ? row.costCategory['Team'] : null,
									costEnvironment:row.costCategory ? row.costCategory['Environment'] : null,
									availabilityZone:row.lineItem['AvailabilityZone'],
									operatingSystem:row.product ? row.product['operatingSystem'] : null,
									purchaseOption:row.savingsPlan['SavingsPlanARN'] ? 'Savings Plan' : row.reservation['ReservationARN'] ? 'Reserved' : row.lineItem['UsageType']?.contains('Spot') ? 'Spot' : 'On Demand',
									tenancy:row.product ? row.product['tenancy'] : null,
									databaseEngine:row.product ? row.product['databaseEngine'] : null,
									billingEntity:row.bill['BillingEntity'],
									itemTerm:row.pricing['term'], regionCode: row.product['region'],lastInvoiceSyncDate: costDate
							]
							Map itemChanged = accountInvoiceService.updateAccountInvoiceItem(itemMatch, itemConfig, null,false,false)
							if(itemChanged.changed == true) {
								itemMatch.dateCheckHash = updateDateCheckHash(startDate,lineItemEndDate,itemMatch.dateCheckHash)
								invoiceItemUpdates << itemMatch
								rtn.invoicesToReconcile << itemMatch.invoiceId
							}
							batchStats.itemUpdate++
						}

					} else {
						//find an invoice match
						AccountInvoice invoiceMatch = invoiceList[resourceId]
						def resourceMatch
						def serverMatch = null
						if(invoiceMatch) {
							resourceMatch = resourceList[resourceId]
							//look by server or what not
						} else if(emptyInvoicesList[resourceId]) {
							invoiceMatch = emptyInvoicesList[resourceId]
						} else {
							//find a resource
							resourceMatch = resourceList[resourceId]
							//if no match. - check for server
							if(!resourceMatch) {
								//check for a server, instance, container
								serverMatch = serverList[resourceId]
								if(serverMatch) {
									//log.debug("querying for resource")
									//create a resource for the server?
									invoiceMatch = serverInvoices[serverMatch[0]]
									// invoiceMatch = AccountInvoice.where{ refType == 'ComputeServer' && refId == resourceMatch.id && period == period && interval == 'month' }.get()
								}
								//check for volume - add it to its server
								if(!serverMatch) {
									resourceMatch = volumeList[resourceId]
									if(resourceMatch) {
										//get what its attached to?
										//create a resource for the volume?
										volMatch = resourceMatch
										serverMatch = volumeServerMatches ? volumeServerMatches[resourceMatch.id] : null
										if(serverMatch) {
											invoiceMatch = serverInvoices[serverMatch[0]]
										} else {
											invoiceMatch = volumeInvoices[resourceMatch.id]
										}
									} else {
										//lb lookup
										resourceMatch = lbList[resourceId]
										if(resourceMatch) {
											invoiceMatch = lbInvoices(resourceMatch.id)
										}
									}
								}
								//add instance / container lookups?
							}
							if(!resourceMatch && !serverMatch ) {
								// println("no resource match...creating item ${lineItemId} ${resourceId}")
								//create one if we have no match
								def scriptConfig = [:]
								// log.info("Creating Resource")
								if(resourceId) {
									def resourceResults = amazonResourceMappingService.createResource(targetZone, row, opts,false)
									if(resourceResults.success == true && resourceResults.data.resource) {
										// println("resourceResults.data.resource: ${resourceResults.data.resource}")
										resourceMatch = resourceResults.data.resource
										resourceList[resourceId] = resourceMatch
										newResourceMade = true
										batchStats.newResource++
									} else {
										//can assign the item to the overall zone invoice
										invoiceMatch = AccountInvoice.where{ refType == 'Cloud' && refId == targetZone.id && period == period && interval == 'month' }.get()
										resourceMatch = targetZone
									}
								} else {
									invoiceMatch = AccountInvoice.where{ refType == 'Cloud' && refId == targetZone.id && period == period && interval == 'month' }.get()
									resourceMatch = targetZone
								}

							}
							//if still no invoice - create one
							if(!invoiceMatch) {
								// log.info("No invoice match for ${rowId}")
								if(resourceMatch || serverMatch) {
									def resourceType
									Boolean appendToServerInvoices = false
									def invoiceConfig = [estimate:false, startDate:startDate, refCategory:'invoice', resourceExternalId: resourceId]
									if(resourceMatch instanceof AccountResource) {
										invoiceConfig = configureResourceInvoice(targetZone, (AccountResource)resourceMatch, invoiceConfig)
										resourceType = 'AccountResource'
									}
									else if(resourceMatch instanceof ComputeServer) {
										invoiceConfig = configureResourceInvoice(targetZone, (ComputeServer)resourceMatch, invoiceConfig)
										resourceType = 'ComputeServer'
										appendToServerInvoices = true
									}
									else if(resourceMatch instanceof Cloud) {
										invoiceConfig = configureResourceInvoice(targetZone, invoiceConfig)
										resourceType = 'Cloud'
									} else if(resourceMatch instanceof NetworkLoadBalancer) {
										invoiceConfig = configureResourceInvoice(targetZone, (NetworkLoadBalancer)resourceMatch, invoiceConfig)
										resourceType = 'NetworkLoadBalancer'
									} else if(serverMatch) {
										invoiceConfig = [refName:serverMatch[1], userId:serverMatch[2], zoneId:serverMatch[3], zoneUUID: serverMatch[12], zoneName:serverMatch[4],
														 siteId:serverMatch[5], planId:serverMatch[6], userName:serverMatch[7], planName:serverMatch[8],
														 layoutId:serverMatch[9], layoutName:serverMatch[10], serverId:serverMatch[0], serverName:serverMatch[1],
														 resourceExternalId:serverMatch[11], refUUID: serverMatch[13], zoneRegion: serverMatch[14], account: resourceAccounts[serverMatch[15]]
										]
										appendToServerInvoices = true
										resourceType = 'ComputeServer'
									} else if(resourceMatch instanceof StorageVolume) {
										invoiceConfig = configureResourceInvoice(targetZone, (StorageVolume)resourceMatch, invoiceConfig)
										resourceType = 'StorageVolume'
									}

									//create it
									invoiceConfig.lastInvoiceSyncDate = costDate
									invoiceConfig.tags = tags

									def invoiceResults = accountInvoiceService.ensureActiveAccountInvoice(targetZone.owner, invoiceConfig.account ?: targetZone.account, resourceType,
											resourceMatch?.id ?: invoiceConfig.serverId, interval, costDate, invoiceConfig, resourceMatch?.uuid ?: invoiceConfig.refUUID, newResourceMade)
									if(invoiceResults.invoice) {
										invoiceMatch = invoiceResults.invoice
										invoiceMatch.save()
										invoiceList[resourceId] = invoiceMatch
										if(resourceMatch instanceof StorageVolume) {
											volumeInvoices[invoiceMatch.refId] = invoiceMatch
										}
										if(resourceMatch instanceof StorageVolume) {
											volumeInvoices[invoiceMatch.refId] = invoiceMatch
										}
										if(serverMatch) {
											serverInvoices[invoiceMatch.refId] = invoiceMatch
										}
										batchStats.newInvoice++
									} else {
										log.info("Warning...Unprocessed Item ${lineItemId} - ${resourceId}")
										batchStats.unprocessed++
									}
								} else {
									log.warn("Unprocessed things")
									//shouldn't get here ever
									batchStats.unprocessed++
								}
							}
						}
						//if we have an invoice
						if(invoiceMatch) {
							def category = categoryForInvoiceItem(row.product['servicecode'],row.lineItem['UsageType'])
							//create the line item
							BigDecimal unblendedRate = parseStringBigDecimal(row.lineItem['UnblendedRate'])
							BigDecimal unblendedCost = parseStringBigDecimal(row.lineItem['UnblendedCost'])
							def itemConfig = [
									refName:invoiceMatch.refName, refCategory:'invoice',
									startDate:lineItemStartDate, endDate:lineItemEndDate, itemId:lineItemId, itemType:row.lineItem['LineItemType'],
									itemName:invoiceMatch.refName, itemDescription:row.lineItem['LineItemDescription'],
									zoneRegion: targetZone.regionCode,
									productCode:row.lineItem['ProductCode'], procutName:row.lineItem['ProductName'],
									itemSeller:row.lineItem['LegalEntity'], itemAction:row.lineItem['Operation'], usageType:row.lineItem['UsageType'],
									usageService:row.product['servicecode'], rateId:row.pricing['RateId'], rateClass:row.lineItem['RateClass'],
									rateUnit:row.pricing['unit'], rateTerm:row.pricing['LeaseContractLength'], itemUsage:parseStringBigDecimal(row.lineItem['UsageAmount']),
									itemRate:unblendedRate, itemCost:unblendedCost,
									onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? parseStringBigDecimal(row.pricing['publicOnDemandCost']) : 0.0,
									amortizedCost: getAmortizedCost(row),
									rateExternalId:row.savingsPlan['SavingsPlanARN'] ?: row.reservation['ReservationARN'],
									costProject:row.costCategory ? row.costCategory['Project'] : null,
									costTeam:row.costCategory ? row.costCategory['Team'] : null,
									costEnvironment:row.costCategory ? row.costCategory['Environment'] : null,
									availabilityZone:row.lineItem['AvailabilityZone'],
									operatingSystem:row.product ? row.product['operatingSystem'] : null,
									purchaseOption:row.savingsPlan['SavingsPlanARN'] ? 'Savings Plan' : row.reservation['ReservationARN'] ? 'Reserved' : row.lineItem['UsageType']?.contains('Spot') ? 'Spot' : 'On Demand',
									tenancy:row.product['tenancy'],
									databaseEngine:row.product['databaseEngine'],
									billingEntity:row.bill ? row.bill['BillingEntity'] : null,
									itemTerm:row.pricing['term'], taxType:row.lineItem['TaxType'], usageCategory: category, uniqueId: rowId,
									regionCode: row.product['region'], lastInvoiceSyncDate: costDate
							]
							//itemPrice:, itemTax:, productId:row.lineItem[''], usageCategory:,
							def itemResults
							if(volMatch) {
								itemResults = accountInvoiceService.createAccountInvoiceItem(invoiceMatch, 'StorageVolume', volMatch.id, lineItemId, itemConfig, false)
							} else {
								itemResults = accountInvoiceService.createAccountInvoiceItem(invoiceMatch, invoiceMatch.refType, invoiceMatch.refId, lineItemId, itemConfig, false)
							}

							itemResults.invoiceItem.dateCheckHash = updateDateCheckHash(startDate,lineItemEndDate,itemResults.invoiceItem.dateCheckHash)
							invoiceItems[itemConfig.uniqueId] = itemResults.invoiceItem
							invoiceItemUpdates << itemResults.invoiceItem
							//add to the invoice
							// itemConfig.tags = tags
							// accountInvoiceService.updateAccountInvoice(invoiceMatch, category, itemConfig,false)
							rtn.invoicesToReconcile << invoiceMatch.id
							// invoiceUpdates << invoiceMatch
							//done
						}
					}
				}
				Date filterEndTime = new Date()
				// log.info("Loop Time: ${filterEndTime.time - filterTime.time}")
			}
		}

		// log.info("Process Time: ${new Date().time - checkTime.time}")
		//invoice updates
		invoiceItemUpdates?.unique()?.each { invoiceItemUpdate ->
			invoiceItemUpdate.save(validate:false)
		}
		invoiceUpdates?.unique()?.each { invoiceUpdate ->
			invoiceUpdate.lastInvoiceSyncDate = costDate
			invoiceUpdate.save(validate:false)
		}
		//batch done
		batchStats.success = true
		log.debug("batch stats: line items: {} -- {} - new invoices: {} - new items: {} - new resources: {} - item updates: {} - unprocessed: {}",
				batchStats.lineItems, batchStats.newInvoice, batchStats.newItem, batchStats.newResource, batchStats.itemUpdate, batchStats.unprocessed)
		//done
		return rtn
	}

	private static getAmortizedCost(row) {
		def cost
		switch (row.lineItem['LineItemType']) {
			case 'SavingsPlanCoveredUsage':
				cost = parseStringBigDecimal(row.savingsPlan['SavingsPlanEffectiveCost'])
				break
			case 'SavingsPlanRecurringFee':
				cost = parseStringBigDecimal(row.savingsPlan['TotalCommitmentToDate']) - parseStringBigDecimal(row.savingsPlan['UsedCommitment'])
				break
			case 'SavingsPlanNegation':
			case 'SavingsPlanUpfrontFee':
				cost = 0.0
				break
			case 'DiscountedUsage':
				cost = parseStringBigDecimal(row.reservation['EffectiveCost'])
				break
			case 'RIFee':
				cost = parseStringBigDecimal(row.reservation['UnusedAmortizedUpfrontFeeForBillingPeriod']) + parseStringBigDecimal(row.reservation['UnusedRecurringFee'])
				break
			default:
				if (row.lineItem['LineItemType'] == 'Fee' && row.reservation['ReservationARN']) {
					cost = 0.0
				} else {
					cost = parseStringBigDecimal(row.lineItem['UnblendedCost'])
				}
		}

		return cost
	}

	private static String categoryForInvoiceItem(String usageService, String usageType) {
		if(usageService == 'AWSDataTransfer') {
			return 'network'
		} else if(usageService == 'AmazonEC2') {
			if(usageType.contains('VolumeUsage')) {
				return 'storage'
			} else {
				return 'compute'
			}
		} else if(usageService == 'AmazonRDS') {
			if(usageType.contains('Storage')) {
				return 'storage'
			} else {
				return 'compute'
			}
		} else {
			if(usageType.contains('Storage')) {
				return 'storage'
			}
			if(usageType.contains('Instance')) {
				return 'compute'
			}
		}
		return null
	}

	protected static File getTemporaryCostFile() {
		File workspace = new File(getWorkspaceStorageLocation())
		File awsCostFolder = new File(workspace,'aws-costing')
		if(!awsCostFolder.exists()) {
			awsCostFolder.mkdirs()
		}

		def uuid = UUID.randomUUID().toString()
		File costFile = new File(awsCostFolder,uuid)
		if(!costFile.exists()) {
			costFile.createNewFile()
		}
		return costFile
	}

	protected static String getWorkspaceStorageLocation() {
		String tmpdir = System.getProperty("java.io.tmpdir")
		return tmpdir
	}

	/**
	 * Refreshes Cloud Costing Data from the Cost Explorer API.
	 * This is not ideal as the primary way to get AWS Cloud costing and is a fallback for certain scenarios. For example,
	 * some MSPs dont allow users to access the Billing Report CUR Bucket file and only expose this API. This API only provides
	 * partial information and is expensive to call. Only use this if the CUR is not accessible and costing is enabled.
	 * @param cloud the current cloud we are running costing for
	 * @param costDate the costDate (date we are running over)
	 * @param opts
	 * @return
	 */
	protected refreshCloudCostFromCostExplorer(Cloud cloud, Date costDate) {
		def rtn = [success:false]
		try {
			def currentDate = new Date()
			costDate = costDate ?: currentDate
			def usageData = loadAwsServiceUsage(cloud, costDate)
			def isCurrentPeriod = InvoiceUtility.getPeriodString(costDate) == InvoiceUtility.getPeriodString(currentDate)
			def forecastData
			if(isCurrentPeriod) {
				forecastData = loadAwsServiceForecast(cloud, costDate)
			} else {
				// refreshing previous period, total is equal to the running
				forecastData = [success:true, cost:usageData.cost]
			}
			//process it
			if(usageData.success == true && usageData.cost > 0) {
				//build pricing update
				LinkedHashMap<String,Object> priceConfig = [runningPrice:usageData.cost, runningCost:usageData.cost]
				//check if we want to use this data
				def costingDepth = getCostingDepth(cloud)
				if(costingDepth == 'summary') {
					//compute breakdown
					priceConfig.computeCost = 0
					def serviceItems = usageData.services.values().collect{ row -> row.items?.findAll{ it.type == 'compute' } ?: [] }?.flatten()
					if(serviceItems)
						priceConfig.computeCost += serviceItems.sum{ it.value ?: 0 }
					priceConfig.computePrice = priceConfig.computeCost
					//storage
					priceConfig.storageCost = 0
					serviceItems = usageData.services.values().collect{ row -> row.items?.findAll{ it.type == 'storage' } ?: [] }?.flatten()
					if(serviceItems)
						priceConfig.storageCost += serviceItems.sum{ it.value ?: 0 }
					priceConfig.storagePrice = priceConfig.storageCost
					//network
					priceConfig.networkCost = 0
					serviceItems = usageData.services.values().collect{ row -> row.items?.findAll{ it.type == 'network' } ?: [] }?.flatten()
					if(serviceItems)
						priceConfig.networkCost += serviceItems.sum{ it.value ?: 0 }
					priceConfig.networkPrice = priceConfig.networkCost
					//other
					priceConfig.extraCost = 0
					serviceItems = usageData.services.values().collect{ row -> row.items?.findAll{ it.type == 'other' } ?: [] }?.flatten()
					if(serviceItems)
						priceConfig.extraCost += serviceItems.sum{ it.value ?: 0 }
					priceConfig.extraPrice = priceConfig.extraCost
					//add forecast
					if(forecastData.success == true && forecastData.cost > 0) {
						priceConfig.totalCost = forecastData.cost
						priceConfig.totalPrice = priceConfig.totalCost
					}
				}
				//add raw data
				priceConfig.rawData = new JsonOutput().toJson([usage:usageData, forecast:forecastData])
				//get the active invoice
				def costConfig = [refName:cloud.name, zoneId:cloud.id, zoneUUID: cloud.uuid, zoneRegion: cloud.regionCode, estimate:true, summaryInvoice:true, startDate:cloud.dateCreated, powerState:'on']
				def invoiceResults = morpheus.async.costing.invoice.ensureActiveAccountInvoice((cloud.owner ?: cloud.account), cloud.account, 'Cloud', cloud.id, 'month', costDate, costConfig,cloud.uuid).blockingGet()
				if(invoiceResults.found == false || invoiceResults.invoice.lastActualDate < costDate) {
					//only updating if an estimate already exists and this is new data - hmm
					updateInvoice(invoiceResults.invoice, costDate, priceConfig, costConfig)
					//done
				}
			}
			rtn.success = usageData.success == true && forecastData.success == true
		} catch(e) {
			log.error("error processing aws zone billing data", e)
		}
		return rtn
	}

	def refreshCloudReservations(Cloud cloud, Date costDate) {
		def rtn = [success:false]
		try {
			costDate = costDate ?: new Date()
			def coverageData = loadAwsReservationCoverage(cloud, costDate)
			def usageData = loadAwsReservationUsage(cloud, costDate)
			def guidanceData = loadAwsReservationGuidance(cloud, costDate)
			def savingsPlanGuidanceData = loadAwsSavingsPlanGuidance(cloud, costDate)
			def reservationsData = loadAwsReservations(cloud)
			//save it all
			if(coverageData.success == true) {
				def coverageCategory = 'zone.reservation.coverage'
				def coverageCode = coverageCategory + '.' + cloud.id + '.' + DateUtility.formatDate(costDate, dateIntervalFormat)
				def coverageRecord = morpheusContext.services.operationData.find(new DataQuery(cloud.owner).withFilters([new DataFilter("refType",'computeZone'),new DataFilter("refId", cloud.id),new DataFilter("code",coverageCode)]))
				for(coverageItem in coverageData.items) {
					def reservationDetails = reservationsData.items.findAll { it.instanceType == coverageItem.key }
					if(reservationDetails) {
						coverageItem.reservation = reservationDetails
					}
				}
				if(coverageRecord == null) {
					def coverageConfig = [account:cloud.account, refType:'computeZone', refId:"${cloud.id}", category:coverageCategory,
										  code:coverageCode, enabled:true, name:'reservation coverage', type:'json', refDate:costDate,
										  keyValue:'reservation-coverage', value:'reservation-coverage', rawData: JsonOutput.toJson(coverageData)]
					coverageRecord = new OperationData(coverageConfig)
					morpheusContext.services.operationData.create(coverageRecord)
				} else {
					coverageRecord.refDate = costDate
					coverageRecord.rawData = JsonOutput.toJson(coverageData)
					morpheusContext.services.operationData.save(coverageRecord)
				}
			}

			if(usageData.success == true) {
				def usageCategory = 'zone.reservation.usage'
				def usageCode = usageCategory + '.' + cloud.id + '.' + DateUtility.formatDate(costDate, dateIntervalFormat)
				def usageRecord = morpheusContext.services.operationData.find(new DataQuery(cloud.owner).withFilters([new DataFilter("refType",'computeZone'),new DataFilter("refId", cloud.id),new DataFilter("code",usageCode)]))

				if(usageRecord == null) {
					def usageConfig = [account:cloud.account, refType:'computeZone', refId:"${cloud.id}", category:usageCategory,
									   code:usageCode, enabled:true, name:'reservation usage', type:'json', refDate:costDate,
									   keyValue:'reservation-usage', value:'reservation-usage', rawData: JsonOutput.toJson(usageData)]
					usageRecord = new OperationData(usageConfig)
					morpheusContext.services.operationData.create(usageRecord)
				} else {
					usageRecord.refDate = costDate
					usageRecord.rawData = JsonOutput.toJson(usageData)
					morpheusContext.services.operationData.save(usageRecord)
				}
			}
			if(guidanceData.success == true) {
				def guidanceCategory = 'zone.reservation.guidance'
				def guidanceCode = guidanceCategory + '.' + cloud.id + '.' + DateUtility.formatDate(guidanceData.periodStart as Date, dateIntervalFormat)
				def guidanceRecord = morpheusContext.services.operationData.find(new DataQuery(cloud.owner).withFilters([new DataFilter("refType",'computeZone'),new DataFilter("refId", cloud.id),new DataFilter("code",guidanceCode)]))

				if(guidanceRecord == null) {
					def guidanceConfig = [account:cloud.account, refType:'computeZone', refId:"${cloud.id}", category:guidanceCategory,
										  code:guidanceCode, enabled:true, name:'reservation guidance', type:'json', refDate:costDate,
										  keyValue:'reservation-guidance', value:'reservation-guidance', rawData: JsonOutput.toJson(guidanceData)]
					guidanceRecord = new OperationData(guidanceConfig)
					morpheusContext.services.operationData.create(guidanceRecord)
				} else {
					guidanceRecord.refDate = costDate
					guidanceRecord.rawData = JsonOutput.toJson(guidanceData)
					morpheusContext.services.operationData.save(guidanceRecord)
				}


			}
			if(savingsPlanGuidanceData.success == true) {
				def guidanceCategory = 'zone.savings.plan.guidance'
				def guidanceCode = guidanceCategory + '.' + cloud.id + '.' + DateUtility.formatDate(savingsPlanGuidanceData.periodStart as Date, dateIntervalFormat)
				def guidanceRecord = morpheusContext.services.operationData.find(new DataQuery(cloud.owner).withFilters([new DataFilter("refType",'computeZone'),new DataFilter("refId", cloud.id),new DataFilter("code",guidanceCode)]))
				if(guidanceRecord == null) {
					def guidanceConfig = [account:cloud.account, refType:'computeZone', refId:"${cloud.id}", category:guidanceCategory,
										  code:guidanceCode, enabled:true, name:'savings plan guidance', type:'json', refDate:costDate,
										  keyValue:'savings-plan-guidance', value:'savings-plan-guidance', rawData: JsonOutput.toJson(savingsPlanGuidanceData)]
					guidanceRecord = new OperationData(guidanceConfig)
					morpheusContext.services.operationData.create(guidanceRecord)
				} else {
					guidanceRecord.refDate = costDate
					guidanceRecord.rawData = JsonOutput.toJson(savingsPlanGuidanceData)
					morpheusContext.services.operationData.save(guidanceRecord)

				}}
			rtn.success = (coverageData.success == true && usageData.success == true && guidanceData.success == true && savingsPlanGuidanceData.success == true)
		} catch(e) {
			log.error("error processing aws zone reservation data", e)
		}
		return rtn
	}

	def loadAwsServiceForecast(Cloud cloud, Date costDate) {
		def rtn = [success:false, forecasts:[], cost:0, unit:null]
		try {
			Date now = new Date()
			costDate = costDate ?: now
			def minStartDate = DateUtility.getStartOfGmtDay(costDate)
			minStartDate = DateUtility.addToDate(minStartDate, Calendar.DAY_OF_YEAR, 1)
			def periodStart = costDate.time > minStartDate.time ? costDate : minStartDate
			def periodEnd = InvoiceUtility.getPeriodEnd(periodStart)
			if(now > periodStart && now < periodEnd) {
				periodStart = now // this should fix range issues
				//get identity info
				def securityClient = AmazonComputeUtility.getAmazonSecurityClient(cloud)
				def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
				log.debug("identityResults: {}", identityResults.results)
				def awsAccountId = identityResults.results?.getAccount()
				def awsUserArn = identityResults.results?.getArn()
				def awsRegion = AmazonProvisionService.getAmazonRegion(cloud)
				def awsRegions = CloudRegion.where { cloud == cloud}.property('externalId').list()
				def isGovAccount = awsUserArn.indexOf('-gov') > -1
				log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
				//get client
				AWSCostExplorer amazonClient = getAmazonCostClient(cloud)
				//get total spend month to day
				def costForecastRequest = new GetCostForecastRequest()
				def dateRange = new DateInterval()
				dateRange.setStart(DateUtility.formatDate(periodStart, dateIntervalFormat))
				log.debug("dateRange start: ${dateRange.getStart()} - full: ${DateUtility.formatDate(periodStart)}")
				dateRange.setEnd(DateUtility.formatDate(periodEnd, dateIntervalFormat))
				costForecastRequest.setTimePeriod(dateRange)
				costForecastRequest.setGranularity('MONTHLY')
				costForecastRequest.setMetric('UNBLENDED_COST')
				//filter
				def requestFilter
				if(isGovAccount) {
					//only filter by region because we don't know the account id link between billing and gov - limitation in api
					requestFilter = new Expression().withDimensions(
							new DimensionValues().withKey('REGION').withValues(awsRegion))
				} else {
					//filter by account of cloud creds and region
					def accountFilter = new Expression().withDimensions(
							new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
					def regionFilter = new Expression().withDimensions(
							new DimensionValues().withKey('REGION').withValues(awsRegions))
					requestFilter = new Expression().withAnd(accountFilter, regionFilter)
				}
				//apply filter
				costForecastRequest.setFilter(requestFilter)
				//load data
				def costForecastResults
				def apiAttempt = 1
				def apiSuccess = false
				while(apiSuccess == false && apiAttempt < 4) {
					try {
						apiAttempt++
						costForecastResults = amazonClient.getCostForecast(costForecastRequest)
						log.debug("forecast results: {}", costForecastResults)
						//{Total: {Amount: 13803.26125375029,Unit: USD},ForecastResultsByTime: [{TimePeriod: {Start: 2018-12-01,End: 2019-01-01},MeanValue: 13803.26125375029,}]}
						def costTotal = costForecastResults.getTotal()
						rtn.cost = costTotal?.getAmount() ? costTotal.getAmount().toBigDecimal() : 0.0G
						//add each forecast
						costForecastResults.getForecastResultsByTime()?.each { costForecast ->
							def row = [start:costForecast.getTimePeriod()?.getStart(), end:costForecast.getTimePeriod()?.getEnd()]
							row.cost = costForecast.getMeanValue() ? costForecast.getMeanValue().toBigDecimal() : 0.0G
							rtn.forecasts << row
						}
						//done
						apiSuccess = true
						//break out
					} catch(LimitExceededException e2) {
						//wait a bit
						log.warn('aws cost explorer rate limit exceeded - retrying')
						sleep(5000 * apiAttempt)
					} catch(e2) {
						log.error("error loading aws service usage: ${e2}", e2)
					}
				}
			}


			//good if we made it here
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws service forecast: ${e}", e)
		}
		return rtn
	}

	def loadAwsServiceUsage(Cloud zone, Date costDate) {
		def rtn = [success:false, services:[:], metrics:[:], cost:0]
		try {
			costDate = costDate ?: new Date()
			Date periodStart = InvoiceUtility.getPeriodStart(costDate)
			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			//get identity info
			def securityClient = AmazonComputeUtility.getAmazonSecurityClient(zone)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonProvisionService.getAmazonRegion(zone)
			def awsRegions = CloudRegion.where { zone == zone}.property('externalId').list()
			def isGovAccount = awsUserArn?.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(zone)
			//get total spend month to day
			def totalCostRequest = new GetCostAndUsageRequest()
			def dateRange = new DateInterval()
			dateRange.setStart(DateUtility.formatDate(periodStart, dateIntervalFormat))
			dateRange.setEnd(DateUtility.formatDate(periodEnd, dateIntervalFormat))
			totalCostRequest.setTimePeriod(dateRange)
			totalCostRequest.setGranularity('MONTHLY')
			def metricList = ['UnblendedCost']
			totalCostRequest.setMetrics(metricList)
			//filtering & grouping
			def requestFilter
			def requestGroups = []
			if(isGovAccount) {
				//only filter by region because we don't know the account id link between billing and gov - limitation in api
				requestFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegion))
			} else {
				//filter by account of cloud creds and region
				def accountFilter = new Expression().withDimensions(
						new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
				def regionFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegions))
				requestFilter = new Expression().withAnd(accountFilter, regionFilter)
			}
			//grouping - global for now
			def groupService = new GroupDefinition()
			groupService.setKey('SERVICE')
			groupService.setType('DIMENSION')
			requestGroups << groupService
			def groupUsageType = new GroupDefinition()
			groupUsageType.setKey('USAGE_TYPE')
			groupUsageType.setType('DIMENSION')
			requestGroups << groupUsageType
			//apply filter
			totalCostRequest.setFilter(requestFilter)
			//apply grouping
			totalCostRequest.setGroupBy(requestGroups)
			//load data
			def totalCostResults
			def nextPageToken
			def apiAttempt = 1
			def apiSuccess = false
			while((apiSuccess == false || nextPageToken) && apiAttempt < 4) {
				try {
					apiAttempt++
					if (nextPageToken) {
						totalCostRequest.setNextPageToken(nextPageToken)
					}
					totalCostResults = amazonClient.getCostAndUsage(totalCostRequest)
					log.debug("results: {}", totalCostResults)
					nextPageToken = totalCostResults.getNextPageToken()
					apiAttempt = 1
					//println("next page token: ${totalCostResults.getNextPageToken()}")
					def servicesList = []
					totalCostResults.getResultsByTime()?.each { timePeriod ->
						timePeriod.getGroups()?.each { group ->
							//keys for the group
							def groupKeys = group.getKeys()
							def costService = groupKeys[0]
							def costGroup = groupKeys[1]
							def costMap = group.getMetrics()['UnblendedCost']
							def costValue = costMap?.getAmount() ? costMap.getAmount().toBigDecimal() : 0.0G
							def costServiceMatch = findBillingService(costService)
							def costCategoryMatch = findBillingCategory(costGroup)
							def costKey = costServiceMatch.code + ' - ' + costGroup
							def categoryMatch = rtn.metrics[costKey]
							if(categoryMatch == null) {
								log.debug("costKey: {} costService: {} costGroup: {}", costKey, costService, costGroup)
								categoryMatch = [code:costServiceMatch.code, name:costServiceMatch.name, key:costKey, service:costService, group:costGroup, items:[],
												 type:costServiceMatch?.priceType, cost:0, count:0]
								rtn.metrics[costKey] = categoryMatch
								servicesList << costServiceMatch.code
							}
							categoryMatch.items << [key:costGroup, value:costValue, type:(costCategoryMatch?.priceType ?: (costServiceMatch?.priceType ?: 'other'))]
							categoryMatch.cost += costValue
							rtn.cost += costValue
							categoryMatch.count++
							//println("key:${groupKey}, value:${costValue}")
						}
					}
					//setup service data
					servicesList = servicesList.unique()
					servicesList?.each { serviceType ->
						rtn.services[serviceType] = [cost:0, count:0, items:[]]
					}

					//show stats
					rtn.metrics.each { key, value ->
						def serviceCode = value.code
						def serviceMatch = rtn.services[serviceCode]
						serviceMatch.cost += value.cost
						serviceMatch.count += value.count
						if(value.items)
							serviceMatch.items.addAll(value.items)
						serviceMatch.type = serviceMatch.type ?: value.type
					}
					log.debug("services: {}", rtn.services)
					//done
					apiSuccess = true
					//break out
				} catch(com.amazonaws.services.costexplorer.model.LimitExceededException e2) {
					//wait a bit
					log.warn('aws cost explorer rate limit exceeded - retrying')
					sleep(5000 * apiAttempt)
				} catch(e3) {
					log.error("error loading aws service usage: ${e3}", e3)
				}
			}
			//good if we made it here
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws service usage: ${e}", e)
		}
		return rtn
	}

	protected loadAwsReservations(Cloud cloud) {
		def rtn = [success:false, items:[]]
		try {
			//get security user
			def securityClient = amazonComputeService.getAmazonSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient)
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonProvisionService.getAmazonRegion(cloud)
			def awsRegions = CloudRegion.where { cloud == cloud}.property('externalId').list()

			def isGovAccount = awsUserArn.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//get client
			AmazonEC2 amazonClient = AmazonComputeUtility.getAmazonClient(cloud)
			//get total spend month to day
			def reservationRequest = new DescribeReservedInstancesRequest()
			def filters = []
			def stateFilter = new Filter()
			stateFilter.setName('state')
			stateFilter.withValues(['active'])
			filters << stateFilter

			reservationRequest.setFilters(filters)
			//load data
			def reservationResults
			def apiAttempt = 1
			def apiSuccess = false
			while(apiSuccess == false && apiAttempt < 4) {
				try {
					apiAttempt++
					reservationResults = amazonClient.describeReservedInstances(reservationRequest)
					log.debug("results: {}", reservationResults)
					//println("next page token: ${totalCostResults.getNextPageToken()}")
					// 	{
					// 		Duration: 31536000,End: Tue Dec 29 18:02:40 UTC 2020,FixedPrice: 0.0,InstanceCount: 200,InstanceType: t2.nano,
					// 		ProductDescription: Linux/UNIX (Amazon VPC),ReservedInstancesId: 5207c17f-fde5-4dc1-94cd-0d0503ac1fe6,
					// 		Start: Mon Dec 30 18:02:41 UTC 2019,State: active,UsagePrice: 0.0,CurrencyCode: USD,InstanceTenancy: default,OfferingClass:
					// 		standard,OfferingType: No Upfront,RecurringCharges: [{Amount: 0.005,Frequency: Hourly}],Scope: Region,Tags: []
					// 		}

					for(reservation in reservationResults.reservedInstances) {
						rtn.items << [
								id           : reservation.reservedInstancesId,
								duration     : reservation.duration,
								start        : reservation.start,
								end          : reservation.end,
								state        : reservation.state,
								instanceCount: reservation.instanceCount,
								instanceType : reservation.instanceType,
								description  : reservation.productDescription,
								currencyCode : reservation.currencyCode,
								offeringClass: reservation.offeringClass,
								offeringType : reservation.offeringType,
								scope        : reservation.scope,
								tags         : reservation.tags
						]
					}

					//done
					apiSuccess = true
					//break out
				} catch(e2) {
					log.error("error loading aws reservation details: ${e2}", e2)
				}
			}
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws reservation details: ${e}", e)
		}
		return rtn
	}

	def loadAwsReservationCoverage(Cloud cloud, Date costDate) {
		def rtn = [success:false, items:[]]
		try {
			costDate = costDate ?: new Date()
			Date periodStart = InvoiceUtility.getPeriodStart(costDate)
			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			if(periodEnd > new Date()) {
				periodEnd = new Date()
			}
			rtn.periodStart = periodStart
			rtn.periodEnd = periodEnd
			//get security user
			def securityClient = AmazonComputeUtility.getAmazonSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonProvisionService.getAmazonRegion(cloud)
			def awsRegions = CloudRegion.where { cloud == cloud}.property('externalId').list()

			def isGovAccount = awsUserArn.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(cloud)
			//get total spend month to day
			def reservationRequest = new GetReservationCoverageRequest()
			def dateRange = new DateInterval()
			dateRange.setStart(DateUtility.formatDate(periodStart, dateIntervalFormat))
			dateRange.setEnd(DateUtility.formatDate(periodEnd, dateIntervalFormat))
			reservationRequest.setTimePeriod(dateRange)
			//reservationRequest.setGranularity('MONTHLY')
			//filtering & grouping
			def requestFilter
			if(isGovAccount) {
				//only filter by region because we don't know the account id link between billing and gov - limitation in api
				requestFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegion))
			} else {
				//filter by account of cloud creds and region
				def accountFilter = new Expression().withDimensions(
						new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
				def regionFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegions))
				requestFilter = new Expression().withAnd(accountFilter, regionFilter)
			}
			//apply filter
			reservationRequest.setFilter(requestFilter)
			//grouping
			def groupType = new GroupDefinition()
			groupType.setKey('INSTANCE_TYPE')
			groupType.setType('DIMENSION')
			def groupList = [groupType]
			reservationRequest.setGroupBy(groupList)
			//metrics
			def metrics = new LinkedList<String>()
			metrics.add('Hour')
			metrics.add('Unit')
			metrics.add('Cost')
			reservationRequest.setMetrics(metrics)
			//load data
			def reservationResults
			def apiAttempt = 1
			def apiSuccess = false
			while(apiSuccess == false && apiAttempt < 4) {
				try {
					apiAttempt++
					reservationResults = amazonClient.getReservationCoverage(reservationRequest)
					log.debug("results: {}", reservationResults)
					//println("next page token: ${totalCostResults.getNextPageToken()}")
					reservationResults.getCoveragesByTime()?.each { timePeriod ->
						timePeriod.getGroups()?.each { group ->
							//keys for the group
							def groupKeys = group.getAttributes()
							def groupCoverage = group.getCoverage()
							def groupCost = groupCoverage.getCoverageCost()
							def groupHours = groupCoverage.getCoverageHours()
							def groupUnits = groupCoverage.getCoverageNormalizedUnits()
							rtn.items << [key                              : groupKeys.instanceType,
										  name                             : groupKeys.instanceType,
										  onDemandHours                    : groupHours.getOnDemandHours(),
										  reservedHours                    : groupHours.getReservedHours(),
										  totalHours                       : groupHours.getTotalRunningHours(),
										  coveragePercent                  : groupHours.getCoverageHoursPercentage(),
										  onDemandCost                     : groupCost.getOnDemandCost(),
										  coverageNormalizedUnitsPercentage: groupUnits.getCoverageNormalizedUnitsPercentage(),
										  onDemandNormalizedUnits          : groupUnits.getOnDemandNormalizedUnits(),
										  reservedNormalizedUnits          : groupUnits.getReservedNormalizedUnits(),
										  totalRunningNormalizedUnits      : groupUnits.getTotalRunningNormalizedUnits()
							]
						}
					}
					//done
					apiSuccess = true
					//break out
				} catch(com.amazonaws.services.costexplorer.model.LimitExceededException e2) {
					//wait a bit
					log.warn('aws cost explorer rate limit exceeded - retrying')
					sleep(5000 * apiAttempt)
				} catch(e2) {
					log.error("error loading aws reservation coverage: ${e2}", e2)
				}
			}
			//good if we made it here
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws reservation usage: ${e}", e)
		}
		return rtn
	}

	def loadAwsReservationUsage(Cloud cloud, Date costDate) {
		def rtn = [success:false, items:[]]
		try {
			costDate = costDate ?: new Date()
			Date periodStart = InvoiceUtility.getPeriodStart(costDate)
			Date lookupStart = InvoiceUtility.getPeriodStart(costDate - 30)
			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			rtn.periodStart = periodStart
			rtn.periodEnd = periodEnd
			//get security user
			def securityClient = AmazonComputeUtility.getAmazonSecurityClient(cloud).amazonClient
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			String awsAccountId = identityResults.results?.getAccount() as String
			def awsUserArn = identityResults.results?.getArn()
			String awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud.regionCode)
			def awsRegions = morpheusContext.async.cloud.region.list(new DataQuery().withFilter(new DataFilter<Long>("zone.id",cloud.id))).map {it.externalId}.toList().blockingGet()

			def isGovAccount = awsUserArn.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(cloud)

			//get total spend month to day
			def reservationRequest = new GetReservationUtilizationRequest()
			def dateRange = new DateInterval()
			dateRange.setStart(DateUtility.formatDate(lookupStart, dateIntervalFormat))
			dateRange.setEnd(DateUtility.formatDate(costDate, dateIntervalFormat))  // can't request past now or AWS gets mad
			reservationRequest.setTimePeriod(dateRange)
			//filtering & grouping
			def requestFilter

			if(isGovAccount) {
				//only filter by region because we don't know the account id link between billing and gov - limitation in api
				requestFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegion))
			} else {
				//filter by account of cloud creds and region
				def accountFilter = new Expression().withDimensions(
						new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
				def regionFilter = new Expression().withDimensions(
						new DimensionValues().withKey('REGION').withValues(awsRegions))
				requestFilter = new Expression().withAnd(accountFilter, regionFilter)
			}
			//apply filter
			reservationRequest.setFilter(requestFilter)
			// group by
			def groupBy = new LinkedList<GroupDefinition>()
			def groupDefinition = new GroupDefinition().withKey("SUBSCRIPTION_ID").withType("DIMENSION")
			groupBy.add(groupDefinition)
			reservationRequest.setGroupBy(groupBy)

			//load data
			def reservationResults
			def apiAttempt = 1
			def apiSuccess = false
			while(apiSuccess == false && apiAttempt < 4) {
				try {
					apiAttempt++
					reservationResults = amazonClient.getReservationUtilization(reservationRequest)
					log.debug("reservation results: {}", reservationResults)
					//println("next page token: ${totalCostResults.getNextPageToken()}")
					reservationResults.getUtilizationsByTime()?.each { timePeriod ->
						def timeStart = timePeriod.getTimePeriod().getStart()
						def timeEnd = timePeriod.getTimePeriod().getEnd()
						timePeriod.getGroups()?.each { group ->
							log.debug("working on group: {}", group)
							//keys for the group
							def groupData = group.getUtilization()
							def row = [start:timeStart, end:timeEnd, amortizedFee:groupData.getAmortizedRecurringFee(), amortizedUpfront:groupData.getAmortizedUpfrontFee(),
									   netSavings:groupData.getNetRISavings(), onDemandCost:groupData.getOnDemandCostOfRIHoursUsed(),
									   purchasedHours:groupData.getPurchasedHours(), purchasedUnits:groupData.getPurchasedUnits(),
									   actualHours:groupData.getTotalActualHours(), actualUnits:groupData.getTotalActualUnits(),
									   totalAmortizedFee:groupData.getTotalAmortizedFee(), potentialSavings:groupData.getTotalPotentialRISavings(),
									   unusedHours:groupData.getUnusedHours(), unusedUnits:groupData.getUnusedUnits(),
									   usagePercent:groupData.getUtilizationPercentage(), usageUnitPercent:groupData.getUtilizationPercentageInUnits()]

							def attrs = group.getAttributes()
							row.subscriptionId = attrs.subscriptionId
							row.instanceType = attrs.instanceType
							row.subscriptionType = attrs.subscriptionType
							row.region = attrs.region
							row.numberOfInstances = attrs.numberOfInstances
							row.offeringType = attrs.offeringType

							rtn.items << row
						}
					}
					//done
					apiSuccess = true
					//break out
				} catch(com.amazonaws.services.costexplorer.model.LimitExceededException e2) {
					//wait a bit
					log.warn('aws cost explorer rate limit exceeded - retrying')
					sleep(5000 * apiAttempt)
				} catch(e2) {
					log.error("error loading aws reservation usage: ${e2}", e2)
				}
			}
			//good if we made it here
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws reservation usage: ${e}", e)
		}
		return rtn
	}

	def loadAwsReservationGuidance(Cloud cloud, Date costDate) {
		def rtn = [success:false, items:[], services:[:]]
		try {
			costDate = costDate ?: new Date()
			def periodStart = InvoiceUtility.getPeriodStart(costDate)
			def periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			rtn.periodStart = periodStart
			rtn.periodEnd = periodEnd
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(cloud)
			//terms
			def termList = ['ONE_YEAR', 'THREE_YEARS']
			//options
			def paymentList = ['NO_UPFRONT', 'PARTIAL_UPFRONT', 'ALL_UPFRONT', 'LIGHT_UTILIZATION', 'MEDIUM_UTILIZATION', 'HEAVY_UTILIZATION']
			//services
			def serviceList = ['Amazon Elastic Compute Cloud - Compute', 'Amazon Relational Database Service', 'Amazon Redshift',
							   'Amazon ElastiCache', 'Amazon Elasticsearch Service']
			//get security user
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			String awsAccountId = identityResults.results?.getAccount() as String
			String awsUserArn = identityResults.results?.getArn() as String
			def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud.regionCode)
			def isGovAccount = awsUserArn.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//find all combinations
			serviceList.each { serviceOption ->
				paymentList.each { paymentOption ->
					termList.each { termOption ->
						def apiAttempt = 1
						def apiSuccess = false
						while(apiSuccess == false && apiAttempt < 4) {
							try {
								apiAttempt++
								//get guidance per service
								def guidanceRequest = new GetReservationPurchaseRecommendationRequest()
								if(isGovAccount != true) {
									guidanceRequest.setAccountId(awsAccountId)
									guidanceRequest.setAccountScope('LINKED')
								} else {
									//hmmm
								}

								def termMonths
								switch(termOption) {
									case 'ONE_YEAR':
										termMonths = 12.0
										break
									case 'THREE_YEARS':
										termMonths = 36.0
										break
								}

								guidanceRequest.setService(serviceOption)
								guidanceRequest.setTermInYears(termOption)
								guidanceRequest.setPaymentOption(paymentOption)
								//load data
								def guidanceResults = amazonClient.getReservationPurchaseRecommendation(guidanceRequest)
								log.debug("results: {}", guidanceResults)
								//println("next page token: ${totalCostResults.getNextPageToken()}")
								guidanceResults.getRecommendations()?.each { guidance ->
									def summary = guidance.getRecommendationSummary()
									def summaryRow = [
											totalSavings       :summary.getTotalEstimatedMonthlySavingsAmount(),
											currencyCode       :summary.getCurrencyCode(),
											totalSavingsPercent:summary.getTotalEstimatedMonthlySavingsPercentage(),
											term               :termOption,
											paymentOption      :paymentOption,
											service            :serviceOption,
											detailList:[]
									]
									//println("payment: ${guidance.getPaymentOption()} term: ${guidance.getTermInYears()} summary: ${summary}")
									guidance.getRecommendationDetails()?.each { row ->
										def detailRow = [
												avgUnitsPerHour      :row.getAverageNormalizedUnitsUsedPerHour(),
												avgInstancesPerHour  :row.getAverageNumberOfInstancesUsedPerHour(),
												avgUtilization       :row.getAverageUtilization(),
												currencyCode         :row.getCurrencyCode(),
												breakEvenMonths      :row.getEstimatedBreakEvenInMonths(),
												monthlyOnDemandCost  :row.getEstimatedMonthlyOnDemandCost(),
												monthlySavings       :row.getEstimatedMonthlySavingsAmount(),
												monthlySavingsPercent:row.getEstimatedMonthlySavingsPercentage(),
												lookbackCost         :row.getEstimatedReservationCostForLookbackPeriod(),
												hourlyMaxUnits       :row.getMaximumNormalizedUnitsUsedPerHour(),
												hourlyMaxInstances   :row.getMaximumNumberOfInstancesUsedPerHour(),
												hourlyMinUnits       :row.getMinimumNormalizedUnitsUsedPerHour(),
												hourlyMinInstances   :row.getMinimumNumberOfInstancesUsedPerHour(),
												recommendedUnits     :row.getRecommendedNormalizedUnitsToPurchase(),
												recommendedInstances :row.getRecommendedNumberOfInstancesToPurchase(),
												montlyCost           :row.getRecurringStandardMonthlyCost(),
												upfrontCost          :row.getUpfrontCost(),
												termCost             :(row.getUpfrontCost()?.toBigDecimal() ?: 0.0) + ((row.getRecurringStandardMonthlyCost()?.toBigDecimal() ?: 0.0) * termMonths)
										]
										def instance
										if(serviceOption == 'Amazon Elastic Compute Cloud - Compute') {
											instance = row.getInstanceDetails().getEC2InstanceDetails()
											detailRow.instanceCategory = instance.getFamily()
											detailRow.instanceType = instance.getInstanceType()
											detailRow.region = instance.getRegion()
										} else if(serviceOption == 'Amazon Relational Database Service') {
											instance = row.getInstanceDetails().getRDSInstanceDetails()
											detailRow.instanceCategory = instance.getFamily()
											detailRow.instanceType = instance.getInstanceType()
											detailRow.instanceEngine = instance.getDatabaseEngine()
											detailRow.region = instance.getRegion()
										} else if(serviceOption == 'Amazon Redshift') {
											instance = row.getInstanceDetails().getRedshiftInstanceDetails()
											detailRow.instanceCategory = instance.getFamily()
											detailRow.instanceType = instance.getNodeType()
											detailRow.region = instance.getRegion()
										} else if(serviceOption == 'Amazon ElastiCache') {
											instance = row.getInstanceDetails().getElastiCacheInstanceDetails()
											detailRow.instanceCategory = instance.getFamily()
											detailRow.instanceType = instance.getNodeType()
											detailRow.region = instance.getRegion()
										} else if(serviceOption == 'Amazon Elasticsearch Service') {
											instance = row.getInstanceDetails().getESInstanceDetails()
											detailRow.instanceCategory = instance.getInstanceClass()
											detailRow.instanceType = instance.getInstanceSize()
											detailRow.region = instance.getRegion()
										}
										summaryRow.detailList << detailRow
									}
									rtn.items << summaryRow
									def serviceMatch = rtn.services[serviceOption]
									if(!serviceMatch) {
										serviceMatch = [name:serviceOption, paymentOptions:[:]]
										rtn.services[serviceOption] = serviceMatch
									}
									def paymentMatch = serviceMatch.paymentOptions[paymentOption]
									if(!paymentMatch) {
										paymentMatch = [name:paymentOption, termOptions:[:]]
										serviceMatch.paymentOptions[paymentOption] = paymentMatch
									}
									def termMatch = paymentMatch.termOptions[termOption]
									if(!termMatch) {
										termMatch = [name:termOption, data:summaryRow]
										paymentMatch.termOptions[termOption] = termMatch
									}
								}
								//done
								apiSuccess = true
								sleep(1000) //sleep 1 second between loops
								//break out
							} catch(LimitExceededException e2) {
								//wait a bit
								log.warn('aws cost explorer rate limit exceeded - retrying')
								sleep(5000 * apiAttempt)
							} catch(e2) {
								log.error("error loading aws pricing data: ${e2}", e2)
							}
						}
					}
				}
			}
			//print it
			/*rtn.services.each { serviceKey, serviceValue ->
				println("service: ${serviceKey}")
				serviceValue.paymentOptions.each { paymentKey, paymentValue ->
					println("  payment: ${paymentKey}")
					paymentValue.termOptions.each { termKey, termValue ->
						def row = termValue.data
						println("    term: ${termKey}")
						println("    - total savings: ${row.totalSavings} percent: ${row.totalSavingsPercent}")
						row.detailList?.each { detailRow ->
							println("    - ${detailRow.instanceCategory} - ${detailRow.instanceType} - min: ${detailRow.hourlyMinInstances} max: ${detailRow.hourlyMaxInstances} ")
							println("      reserve count: ${detailRow.recommendedInstances} cost: ${detailRow.monthlyOnDemandCost} savings: ${detailRow.monthlySavings}")
						}
					}
				}
			}*/
			//good if we made it here
			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws reservation usage: ${e}", e)
		}
		return rtn
	}

	def loadAwsSavingsPlanGuidance(Cloud zone, Date costDate) {
		def rtn = [success:false, items:[], lookbacks:[:]]
		try {
			costDate = costDate ?: new Date()
			Date periodStart = InvoiceUtility.getPeriodStart(costDate)
			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			rtn.periodStart = periodStart
			rtn.periodEnd = periodEnd
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(zone)
			//terms
			def termList = ['ONE_YEAR', 'THREE_YEARS']
			//options
			def paymentList = ['NO_UPFRONT', 'PARTIAL_UPFRONT', 'ALL_UPFRONT']
			//services
			def serviceList = ['COMPUTE_SP', 'EC2_INSTANCE_SP' ]
			// lookback period S
			def lookbackPeriodList = ['SEVEN_DAYS', 'THIRTY_DAYS', 'SIXTY_DAYS']
			//get security user
			def securityClient = AmazonComputeUtility.getAmazonSecurityClient(zone).amazonClient
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			String awsAccountId = identityResults.results?.getAccount() as String
			String awsUserArn = identityResults.results?.getArn() as String
			def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(zone.regionCode)
			def isGovAccount = awsUserArn.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//find all combinations
			lookbackPeriodList.each { lookbackPeriodOption ->
				serviceList.each { serviceOption ->
					paymentList.each { paymentOption ->
						termList.each { termOption ->
							def apiAttempt = 1
							def apiSuccess = false
							def termMonths
							switch(termOption) {
								case 'ONE_YEAR':
									termMonths = 12.0
									break
								case 'THREE_YEARS':
									termMonths = 36.0
									break
							}
							while(apiSuccess == false && apiAttempt < 4) {
								try {
									apiAttempt++
									//get guidance per savings plan type
									def guidanceRequest = new GetSavingsPlansPurchaseRecommendationRequest()
									def requestFilter
									if(isGovAccount == true) {
										// dunno..?
									} else {
										requestFilter = new Expression().withDimensions(
												new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
									}

									// guidanceRequest.setFilter(requestFilter)
									guidanceRequest.setAccountScope('LINKED')
									guidanceRequest.setSavingsPlansType(serviceOption)
									guidanceRequest.setTermInYears(termOption)
									guidanceRequest.setPaymentOption(paymentOption)
									guidanceRequest.setLookbackPeriodInDays(lookbackPeriodOption)
									def lookbackPeriodDays
									switch(lookbackPeriodOption) {
										case 'THIRTY_DAYS':
											lookbackPeriodDays = 30.0
											break
										case 'SEVEN_DAYS':
											lookbackPeriodDays = 7.0
											break
										case 'SIXTY_DAYS':
											lookbackPeriodDays = 60.0
											break
									}
									//load data
									def guidanceResults = amazonClient.getSavingsPlansPurchaseRecommendation(guidanceRequest)
									// log.debug("results: {}", guidanceResults)
									//println("next page token: ${totalCostResults.getNextPageToken()}")
									def guidance = guidanceResults.getSavingsPlansPurchaseRecommendation()
									def summary = guidance.getSavingsPlansPurchaseRecommendationSummary()
									def summaryRow = [totalSavings:summary?.getEstimatedMonthlySavingsAmount(), currencyCode:summary?.getCurrencyCode(),
													  totalSavingsPercent:summary?.getEstimatedSavingsPercentage(), term:termOption,
													  paymentOption:paymentOption, service:serviceOption, detailList:[]
									]
									//println("payment: ${guidance.getPaymentOption()} term: ${guidance.getTermInYears()} summary: ${summary}")
									guidance.getSavingsPlansPurchaseRecommendationDetails()?.each { row ->
										def detailRow = [
												currentAverageHourlyOnDemandSpend:row.getCurrentAverageHourlyOnDemandSpend(),
												currentMaximumHourlyOnDemandSpend:row.getCurrentMaximumHourlyOnDemandSpend(),
												currentMinimumHourlyOnDemandSpend:row.getCurrentMinimumHourlyOnDemandSpend(),
												currentMonthlySpend:((row.getCurrentAverageHourlyOnDemandSpend()?.toBigDecimal() ?: 0.0) * 730.0),
												estimatedAverageUtilization:row.getEstimatedAverageUtilization(),
												monthlySavings:row.getEstimatedMonthlySavingsAmount(),
												monthlySavingsPercent:row.getEstimatedSavingsPercentage(),
												monthlyOnDemandCost:row.getEstimatedOnDemandCost(),
												estimatedHourlyOnDemandCost:(row.getEstimatedOnDemandCost()?.toBigDecimal() ?: 0.0) / lookbackPeriodDays / 24.0,
												estimatedOnDemandCostWithCurrentCommitment:row.getEstimatedOnDemandCostWithCurrentCommitment(),
												estimatedROI:row.getEstimatedROI(),
												estimatedTotalSpend: (((row.getEstimatedOnDemandCost()?.toBigDecimal() ?: 0.0) / lookbackPeriodDays / 24.0) * 730.0) + (((row.getEstimatedSPCost()?.toBigDecimal() ?: 0.0) / lookbackPeriodDays / 24.0) * 730.0),
												hourlyCommitmentToPurchase:row.getHourlyCommitmentToPurchase(),
												upfrontCost:row.getUpfrontCost(),
												termCost:(row.getHourlyCommitmentToPurchase()?.toBigDecimal() ?: 0.0) * 730.0 * termMonths
										]

										def instance = row.getSavingsPlansDetails()
										detailRow.instanceFamily = serviceOption
										detailRow.instanceCategory = instance.getInstanceFamily()
										detailRow.offeringId = instance.getOfferingId()
										detailRow.region = instance.getRegion()
										summaryRow.detailList << detailRow
									}
									rtn.items << summaryRow


									def lookbackMatch = rtn.lookbacks[lookbackPeriodOption]
									if(!lookbackMatch) {
										lookbackMatch = [name:lookbackPeriodOption, services:[:]]
										rtn.lookbacks[lookbackPeriodOption] = lookbackMatch
									}
									def serviceMatch = lookbackMatch.services[serviceOption]
									if(!serviceMatch) {
										serviceMatch = [name:serviceOption, paymentOptions:[:]]
										lookbackMatch.services[serviceOption] = serviceMatch
									}
									def paymentMatch = serviceMatch.paymentOptions[paymentOption]
									if(!paymentMatch) {
										paymentMatch = [name:paymentOption, termOptions:[:]]
										serviceMatch.paymentOptions[paymentOption] = paymentMatch
									}
									def termMatch = paymentMatch.termOptions[termOption]
									if(!termMatch) {
										termMatch = [name:termOption, data:summaryRow]  // finally.. add the row to the term under the payment, under the service
										paymentMatch.termOptions[termOption] = termMatch
									}

									//done
									apiSuccess = true
									sleep(1000) //sleep 1 second between loops
									//break out
								} catch(com.amazonaws.services.costexplorer.model.LimitExceededException e2) {
									//wait a bit
									log.warn('aws cost explorer rate limit exceeded - retrying')
									sleep(5000 * apiAttempt)
								} catch(e2) {
									log.error("error loading aws pricing data: ${e2}", e2)
								}
							}
						}
					}
				}
			}

			rtn.success = true
			//done
		} catch(e) {
			log.error("error processing aws service plan usage: ${e}", e)
		}
		return rtn
	}

	//TODO: These are a part of a custom report and need to be moved out to the report provider when implemented
//	def loadAwsSavingsPlanUsage(Cloud zone, Date costDate, String interval, Map opts) {
//		def rtn = [success:false, items:[]]
//		try {
//			costDate = costDate ?: new Date()
//			def periodStart = opts.periodStart ?: MorpheusUtils.getPeriodStart(costDate, interval)
//			def lookupStart = opts.lookupStart ?: MorpheusUtils.getPeriodStart(costDate - 30, interval)
//			def periodEnd = opts.periodEnd ?: MorpheusUtils.getPeriodEnd(costDate, interval)
//			rtn.periodStart = periodStart
//			rtn.periodEnd = periodEnd
//			//get security user
//			def securityClient = amazonComputeService.getAmazonSecurityClient(zone)
//			def identityResults = AmazonComputeUtility.getClientItentity(securityClient, opts)
//			log.debug("identityResults: {}", identityResults.results)
//			def awsAccountId = identityResults.results?.getAccount()
//			def awsUserArn = identityResults.results?.getArn()
//			def awsRegion = AmazonProvisionService.getAmazonRegion(zone)
//			def isGovAccount = awsUserArn.indexOf('-gov') > -1
//			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
//			//get client
//			opts.amazonClient = opts.amazonClient ?: getAmazonCostClient(zone)
//			//get total spend month to day
//			def utilizationRequest = new GetSavingsPlansUtilizationDetailsRequest()
//			utilizationRequest.setMaxResults(100000)
//			def dateRange = new DateInterval()
//			dateRange.setStart(DateUtility.formatDate(lookupStart, dateIntervalFormat))
//			dateRange.setEnd(DateUtility.formatDate(periodEnd, dateIntervalFormat))
//			utilizationRequest.setTimePeriod(dateRange)
//
//			//filtering & grouping
//			def requestFilter
//
//			if(isGovAccount) {
//				//only filter by region because we don't know the account id link between billing and gov - limitation in api
//				requestFilter = new Expression().withDimensions(
//						new DimensionValues().withKey('REGION').withValues(awsRegion))
//			} else {
//				//filter by account of cloud creds and region
//				requestFilter = new Expression().withDimensions(
//						new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
//			}
//			//apply filter
//			utilizationRequest.setFilter(requestFilter)
//			//load data
//			def utilizationResults
//			def apiAttempt = 1
//			def apiSuccess = false
//			while(apiSuccess == false && apiAttempt < 4) {
//				try {
//					apiAttempt++
//					utilizationResults = opts.amazonClient.getSavingsPlansUtilizationDetails(utilizationRequest)
//					log.debug("saving plan results: {}", utilizationResults)
//					//println("next page token: ${totalCostResults.getNextPageToken()}")
//					println "hhh"
//					def timeStart = utilizationResults.getTimePeriod().getStart()
//					def timeEnd = utilizationResults.getTimePeriod().getEnd()
//					utilizationResults.getSavingsPlansUtilizationDetails()?.each { details ->
//
//						def amortizedCommitment = details.getAmortizedCommitment()
//						def savings = details.getSavings()
//						def utilization = details.getUtilization()
//
//						def row = [start:timeStart, end:timeEnd,
//								   totalCommitment: utilization.getTotalCommitment(),
//								   unusedCommitment: utilization.getUnusedCommitment(),
//								   usedCommitment: utilization.getUsedCommitment(),
//								   utilizationPercentage: utilization.getUtilizationPercentage(),
//								   savingsPlanArn: details.getSavingsPlanArn(),
//								   netSavings: savings.getNetSavings(),
//								   onDemandCostEquivalent: savings.getOnDemandCostEquivalent(),
//								   amortizedRecurringCommitment: amortizedCommitment.getAmortizedRecurringCommitment(),
//								   amortizedUpfrontCommitment: amortizedCommitment.getAmortizedUpfrontCommitment(),
//								   totalAmortizedCommitment: amortizedCommitment.getTotalAmortizedCommitment()]
//
//						def attrs = details.getAttributes()
//						row.instanceFamily = attrs.InstanceFamily
//						row.savingsPlansType = attrs.SavingsPlansType
//						row.upfrontFee = attrs.UpfrontFee
//						row.purchaseTerm = attrs.PurchaseTerm
//						row.paymentOption = attrs.PaymentOption
//						row.hourlyCommitment = attrs.HourlyCommitment
//						row.recurringHourlyFee = attrs.RecurringHourlyFee
//
//						rtn.items << row
//					}
//					//done
//					apiSuccess = true
//					//break out
//				} catch(com.amazonaws.services.costexplorer.model.LimitExceededException e2) {
//					//wait a bit
//					log.warn('aws cost explorer rate limit exceeded - retrying')
//					sleep(5000 * apiAttempt)
//				} catch(com.amazonaws.services.costexplorer.model.DataUnavailableException e3) {
//					// filters, time period, etc has no data.. not really an error
//					log.warn('no data available')
//					apiSuccess = true
//				} catch(e2) {
//					log.error("error loading aws savings plan usage: ${e2}", e2)
//				}
//			}
//			//good if we made it here
//			rtn.success = true
//			//done
//		} catch(e) {
//			log.error("error processing aws savings plan usage: ${e}", e)
//		}
//		return rtn
//	}
//
//	def loadAwsSavingsPlanCoverage(Cloud zone, Date costDate, String interval) {
//		def rtn = [success:false, items:[]]
//		try {
//			costDate = costDate ?: new Date()
//			Date periodStart = InvoiceUtility.getPeriodStart(costDate, interval)
//			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate, interval)
//			rtn.periodStart = periodStart
//			rtn.periodEnd = periodEnd
//			//get security user
//			def securityClient = amazonComputeService.getAmazonSecurityClient(zone)
//			def identityResults = AmazonComputeUtility.getClientItentity(securityClient, [:])
//			log.debug("identityResults: {}", identityResults.results)
//			def awsAccountId = identityResults.results?.getAccount()
//			def awsUserArn = identityResults.results?.getArn()
//			def awsRegion = AmazonProvisionService.getAmazonRegion(zone)
//			def isGovAccount = awsUserArn.indexOf('-gov') > -1
//			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
//			//get client
//			AWSCostExplorer amazonClient = getAmazonCostClient(zone)
//			//get total spend month to day
//			def coverageRequest = new GetSavingsPlansCoverageRequest()
//			coverageRequest.setMaxResults(100000)
//			def dateRange = new DateInterval()
//			dateRange.setStart(DateUtility.formatDate(periodStart, dateIntervalFormat))
//			dateRange.setEnd(DateUtility.formatDate(periodEnd, dateIntervalFormat))
//			coverageRequest.setTimePeriod(dateRange)
//
//			//filtering & grouping
//			def requestFilter
//
//			if(isGovAccount) {
//				//only filter by region because we don't know the account id link between billing and gov - limitation in api
//				requestFilter = new Expression().withDimensions(
//						new DimensionValues().withKey('REGION').withValues(awsRegion))
//			} else {
//				//filter by account of cloud creds and region
//				def accountFilter = new Expression().withDimensions(
//						new DimensionValues().withKey('LINKED_ACCOUNT').withValues(awsAccountId))
//				def regionFilter = new Expression().withDimensions(
//						new DimensionValues().withKey('REGION').withValues(awsRegion))
//				requestFilter = new Expression().withAnd(accountFilter, regionFilter)
//			}
//			//apply filter
//			coverageRequest.setFilter(requestFilter)
//			//grouping
//			def groupType = new GroupDefinition()
//			groupType.setKey('INSTANCE_TYPE_FAMILY')
//			groupType.setType('DIMENSION')
//			def groupTypeService = new GroupDefinition()
//			groupTypeService.setKey('SERVICE')
//			groupTypeService.setType('DIMENSION')
//			def groupTypeRegion = new GroupDefinition()
//			groupTypeRegion.setKey('REGION')
//			groupTypeRegion.setType('DIMENSION')
//			def groupList = [groupType, groupTypeService, groupTypeRegion]
//			coverageRequest.setGroupBy(groupList)
//			//metrics
//			def metrics = new LinkedList<String>()
//			metrics.add('SpendCoveredBySavingsPlans')
//			coverageRequest.setMetrics(metrics)
//			//load data
//			def coverageResults
//			def apiAttempt = 1
//			def apiSuccess = false
//			while(apiSuccess == false && apiAttempt < 4) {
//				try {
//					apiAttempt++
//					coverageResults = amazonClient.getSavingsPlansCoverage(coverageRequest)
//					log.debug("saving plan results: {}", coverageResults)
//					//println("next page token: ${totalCostResults.getNextPageToken()}")
//
//					coverageResults.getSavingsPlansCoverages()?.each { c ->
//
//						def timeStart = c.getTimePeriod().getStart()
//						def timeEnd = c.getTimePeriod().getEnd()
//						def coverage = c.getCoverage()
//						def attrs = c.getAttributes()
//						println "attrs: ${attrs}"
//
//						def row = [start:timeStart, end:timeEnd,
//								   coveragePercentage: coverage.getCoveragePercentage(),
//								   onDemandCost: coverage.getOnDemandCost(),
//								   spendCoveredBySavingsPlans: coverage.getSpendCoveredBySavingsPlans(),
//								   totalCost: coverage.getTotalCost(),
//								   instanceTypeFamily: attrs.INSTANCE_TYPE_FAMILY,
//								   service: attrs.SERVICE,
//								   region: attrs.REGION
//						]
//						rtn.items << row
//					}
//					//done
//					apiSuccess = true
//					//break out
//				} catch(LimitExceededException e2) {
//					//wait a bit
//					log.warn('aws cost explorer rate limit exceeded - retrying')
//					sleep(5000 * apiAttempt)
//				} catch(DataUnavailableException e3) {
//					// filters, time period, etc has no data.. not really an error
//					log.warn('no data available')
//					apiSuccess = true
//				} catch(e2) {
//					log.error("error loading aws savings plan coverage: ${e2}", e2)
//				}
//			}
//			//good if we made it here
//			rtn.success = true
//			//done
//		} catch(e) {
//			log.error("error processing aws savings plan coverage: ${e}", e)
//		}
//		return rtn
//	}



	protected loadAwsBillingReportDefinition(Cloud cloud) {
		def rtn = null
		try {
			def costingReport = cloud.getConfigProperty('costingReport')
			//def costingBucket = zone.getConfigProperty('costingBucket')
			def reportResults = loadReportDefinitions(cloud)

			if(reportResults.success) {
				reportResults.reports?.each { report ->
					if (report.getReportName() == costingReport /*&& report.getS3Bucket() == costingBucket*/) {
						rtn = report
					}
				}
			}
		} catch(e) {
			log.error("error loading cost report list: ${e}", e)
		}
		return rtn
	}

	def loadAwsReportDefinitions(Cloud zone) {
		def rtn = [success:false]
		try {
			def amazonClient = AmazonComputeUtility.getAmazonCostReportClient(zone)
			def reportDefResult = AmazonComputeUtility.getCostingReportDefinitions(amazonClient, [:])
			if(reportDefResult.success) {
				rtn.success = true
				rtn.reports = []

				for(def report in reportDefResult.results.reportDefinitions) {
					if(report.timeUnit == 'HOURLY' && report.format == 'textORcsv' && report.compression == 'GZIP' && report.additionalSchemaElements?.contains('RESOURCES')) {
						rtn.reports << report
					}
				}
			}
		} catch(e) {
			log.error("error loading cost report list: ${e}", e)
		}
		rtn
	}

	//create bucket / report if needed
	def initializeCosting(Cloud zone, Map opts = [:]) {
		def rtn = [success:true]
		if(zone.getConfigProperty('costingReport') == 'create-report' /* && !zone.getConfigProperty('costingReportCreateError')*/) {
			def costingReportName = zone.getConfigProperty('costingReportName')
			def costingBucketName = zone.getConfigProperty('costingBucket')
			def costingFolder = zone.getConfigProperty('costingFolder')
			def costingRegion = zone.getConfigProperty('costingRegion') ?: AmazonComputeUtility.getAmazonEndpointRegion(AmazonComputeUtility.getAmazonEndpoint(zone))
			def costingReport
			def costingBucket

			if(costingBucketName == 'create-bucket') {
				costingBucketName = zone.getConfigProperty('costingBucketName')

				// check if already exists
				costingBucket = morpheusContext.services.storageBucket.find(
					new DataQuery().withFilters([
						new DataFilter('bucketName', costingBucketName),
						new DataFilter('account.id', zone.account.id),
						new DataFilter('storageServer.refType', 'ComputeZone'),
						new DataFilter('storageServer.refId', zone.id)
					])
				)

				if(!costingBucket) {
					def createBucketResult = createReportBucket(zone, costingBucketName, costingRegion)
					if (createBucketResult.success) {
						costingBucket = createBucketResult.bucket
					} else {
						morpheus.async.cloud.updateCloudCostStatus(zone, Cloud.Status.error, createBucketResult.msg, new Date())
						rtn.success = false
						log.error(zone.costStatusMessage)
					}
				}
			} else {
				costingBucket = morpheusContext.services.storageBucket.find(
					new DataQuery().withFilters([
						new DataFilter('bucketName', costingBucketName),
						new DataFilter('account.id', zone.account.id),
						new DataFilter('storageServer.refType', 'ComputeZone'),
						new DataFilter('storageServer.refId', zone.id)
					])
				)

				if(!costingBucket) {
					// bucket not discovered yet, skipping report creation
					morpheus.async.cloud.updateCloudCostStatus(zone, Cloud.Status.error, "Waiting for costing bucket ${costingBucketName} discovery", new Date())
					rtn.success = false
					log.warn("Waiting for costing bucket ${costingBucketName} discovery")
				} else if(costingBucket.regionCode) {
					costingRegion = costingBucket.regionCode
				}
			}

			if(rtn.success) {
				def reportResults = loadAwsReportDefinitions(zone)
				costingReport = reportResults.reports?.find {
					it.reportName == costingReportName &&
					it.s3Bucket == costingBucketName &&
					it.s3Region == costingRegion &&
					it.s3Prefix == costingFolder
				}

				// create report
				if (!costingReport) {
					def createReportResult = createReportDefinition(zone, costingBucket, [
						name: costingReportName, bucket: costingBucket, folder: costingFolder
					])
					if (createReportResult.success) {
						costingReport = createReportResult.report
					} else {
						morpheus.async.cloud.updateCloudCostStatus(zone, Cloud.Status.error, createReportResult.error.msg, new Date())
						rtn.success = false
						log.error(zone.costStatusMessage)
					}
				}

				if (costingReport) {
					zone.setConfigProperty('costingReport', costingReport.reportName)
					zone.setConfigProperty('costingBucket', costingReport.s3Bucket)
					zone.setConfigProperty('costingRegion', costingReport.s3Region)
					zone.setConfigProperty('costingFolder', costingReport.s3Prefix)
					zone.setConfigProperty('costingReportName', null)
					morpheusContext.services.cloud.save(zone)
				}
			}
		}
		rtn
	}

	//create the definition if it doesn't exist
	def createReportDefinition(Cloud cloud, StorageBucket bucket, Map opts = [:]) {
		def rtn = [success:false, report:null]
		try {
			// bucket region can be diff from zone region
			def region = bucket.getConfigProperty('region') ?: cloud.getConfigProperty('costingRegion') ?: AmazonComputeUtility.getAmazonEndpointRegion(AmazonComputeUtility.getAmazonEndpoint(cloud))
			def s3Client = AmazonComputeUtility.getAmazonS3Client(cloud, region)
			def reportConfig = [
				name: opts.name ?: opts.costingReportName, bucket: bucket.bucketName, region: region, prefix: opts.folder ?: '', artifacts: opts.artifacts
			]

			def bucketPolicyResult = AmazonComputeUtility.getBucketPolicy(s3Client, bucket.bucketName)

			if(bucketPolicyResult.success) {
				def policy = new groovy.json.JsonSlurper().parseText(bucketPolicyResult.bucketPolicy.policyText ?: '{"Version": "2008-10-17", "Statement": []}')

				// ensure needed policy stmts on reporting bucket
				def reqStmts = [
					[service: 'billingreports.amazonaws.com', actions: ['s3:GetBucketAcl', 's3:GetBucketPolicy'], resource: "arn:aws:s3:::${bucket.bucketName}"],
					[service: 'billingreports.amazonaws.com', actions: ['s3:PutObject'], resource: "arn:aws:s3:::${bucket.bucketName}/*"]
				]

				def updatePolicy = false

				for(def reqStmt : reqStmts) {
					def match

					for(def stmt : policy.Statement) {
						if(stmt.Principal instanceof Map && stmt.Principal?.Service == reqStmt.service && stmt.Resource == reqStmt.resource) {
							match = stmt
							break
						}
					}

					if(match) {
						if(match.Effect != 'Allow') {
							match.Effect = 'Allow'
							updatePolicy = true
						}
						for(def reqAction : reqStmt.actions) {
							match.Action = match.Action instanceof String ? [match.Action] : (match.Action ?: [])
							if(!match.Action.find { it == reqAction }) {
								match?.Action << reqAction
								updatePolicy = true
							}
						}
					}
					else {
						policy.Statement = (policy.Statement ?: []) + [
								Effect: 'Allow', Principal: [Service: reqStmt.service], Action: reqStmt.actions, Resource: reqStmt.resource
						]
						updatePolicy = true
					}
				}

				def updatePolicyResult

				if(updatePolicy) {
					updatePolicyResult = AmazonComputeUtility.setBucketPolicy(s3Client, bucket.bucketName, JsonOutput.toJson(policy))
				}

				if(!updatePolicy || updatePolicyResult?.success) {
					// create definition
					def reportResult = AmazonComputeUtility.createCostingReportDefinition(AmazonComputeUtility.getAmazonCostReportClient(cloud), reportConfig + compatibleReportConfig)

					if(reportResult.success) {
						rtn.success = true
						rtn.report = loadAwsReportDefinitions(cloud).reports.find { it.reportName == reportConfig.name }
					}
					else {
						rtn.error = [msg: "Unable to create costing report: ${reportResult.msg}", field: 'costingReport']
					}
				}
				else {
					rtn.error = [msg: "Unable to update costing bucket policy for ${bucket.bucketName}: ${updatePolicyResult.msg}", field: 'costingBucket']
				}
			}
			else {
				rtn.error = [msg: "Unable to use bucket for costing report: ${bucketPolicyResult.msg}", field: 'costingBucket']
			}
		} catch(e) {
			log.error("error creating costing report: ${e}", e)
			rtn.error = [msg: "Unable to create costing report due to error: ${e.message}", field: 'costingReport']
		}
		log.info("create costing report results: $rtn")
		rtn
	}

	def createReportBucket(Cloud zone, String bucketName, String regionCode, Map opts = [:]) {
		def rtn = [success:false, bucket:null]
		try {
			StorageServer storageServer = morpheusContext.services.storageServer.find(new DataQuery().withFilter('refType', 'ComputeZone').withFilter('refId', zone.id))
			def storageBucketOpts = [
				account: zone.account, providerType: 's3', name: bucketName, bucketName: bucketName, active: true,
				storageServer: storageServer
			]
			def storageBucket = new StorageBucket(storageBucketOpts)
			storageBucket.regionCode = regionCode
			def createResult = plugin.storageProvider.createBucket(storageBucket)

			if(createResult.success) {
				rtn.success = true
				rtn.bucket = storageBucket
			}
			else {
				rtn.msg = "Unable to create costing report bucket: ${createResult.msg}"

				if(rtn.msg?.contains('(Service:')) {
					rtn.msg = rtn.msg.substring(0, rtn.msg.indexOf('(Service:'))
				}
			}
		} catch(e) {
			log.error("error creating costing report bucket: ${e}", e)
		}
		rtn
	}


	private List processBillingHeader(Map manifest, CsvRow header) {
		List rtn = [] //ordered list of columns
		def columnList = header?.getFields()
		columnList?.eachWithIndex{ column, index ->
			//format is category / name
			def columnTokens = column.tokenize('/')
			if(columnTokens.size() == 2) {
				def row = [index:index, category:columnTokens[0], name:columnTokens[1], raw:column]
				def matchRow = manifest.columns?.find{ it.category == row.category && it.name == row.name }
				if(matchRow)
					row.type = matchRow.type
				rtn << row
			}
		}
		return rtn
	}

	@CompileStatic
	private Map<String,Object> processBillingLine(Collection columns, CsvRow line) {
		// row.lineItem['BlendedCost']
		Map<String,Object> rtn = [:]
		// Map<String,String> lineItem = [:]
		// lineItem['BlendedCost'] = 'blah'
		// rtn.lineItem = lineItem
		String[] lineTokens = line.getFields().toArray() as String[]
		Integer rowSize = lineTokens.size()
		for(Integer index=0;index < rowSize;index++) {
			Map<String,Object> column = columns[index] as Map<String,Object>
			Map<String,Object> categoryMatch = rtn[column.category as String]  as Map<String,Object>
			if(!categoryMatch) {
				categoryMatch = [:]
				String category = column.category as String
				rtn[category] = categoryMatch
			}
			//value
			String columnName = column.name as String
			categoryMatch[columnName] = processBillingLineColumn(column.type as String, lineTokens[index] as String)
			//special interval processing
			if((column.type as String) == 'Interval' && categoryMatch[columnName]) {
				String[] intervalRange = (categoryMatch[columnName] as String).tokenize('/')
				if(intervalRange.size() == 2) {
					String startIntervalName = columnName + 'Start'
					String endIntervalName = columnName + 'End'
					categoryMatch[startIntervalName] = DateUtility.parseDateWithFormat(intervalRange[0],"yyyy-MM-dd'T'HH:mm:ss'Z'")
					categoryMatch[endIntervalName] = DateUtility.parseDateWithFormat(intervalRange[1],"yyyy-MM-dd'T'HH:mm:ss'Z'")
				}
			}
		}

		return rtn
	}


	@CompileStatic
	private processBillingLineColumn(String type, String value) {
		Object rtn
		if(type == 'String' || type == 'OptionalString') {
			if(value?.startsWith('"') && value?.endsWith('"'))
				rtn = value.substring(1, value.length() - 1)
			else
				rtn = value
		} else if(type == 'DateTime'){
			rtn = DateUtility.parseDateWithFormat(value,"yyyy-MM-dd'T'HH:mm:ss'Z'")//, billingDateTimeFormat)
		} else if(type == 'BigDecimal' || type == 'OptionalBigDecimal') {
			rtn = value ? value.toBigDecimal() : null
		} else if(type == 'Interval') {
			rtn = value
		} else {
			rtn = value
		}
		return rtn
	}

	@CompileStatic
	private static String getLineItemHash(String rowId) {
		MessageDigest digest = MessageDigest.getInstance("SHA-256")
		// return rowId
		return digest.digest(rowId.getBytes("UTF-8")).encodeHex().toString()
	}


	protected static AWSCostExplorer getAmazonCostClient(Cloud cloud) {
		AmazonComputeUtility.getAmazonCostClient(cloud)
	}

	public class InvoiceProcessResult {
		public HashSet<Long> invoicesToReconcile = new HashSet<Long>()
		public List<String> usageZones = new ArrayList<>()
		
	}

	static Double parseStringBigDecimal(str, defaultValue = null) {
		BigDecimal rtn = defaultValue as BigDecimal
		try { rtn = str.toBigDecimal() } catch(e) {}
		return rtn
	}
}
