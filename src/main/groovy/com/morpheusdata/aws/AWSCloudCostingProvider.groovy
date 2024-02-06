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
import com.morpheusdata.core.BulkCreateResult
import com.morpheusdata.core.BulkSaveResult
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.AbstractCloudCostingProvider
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.core.util.InvoiceUtility
import com.morpheusdata.core.util.MorpheusUtils
import com.morpheusdata.model.AccountInvoice
import com.morpheusdata.model.AccountResource
import com.morpheusdata.model.AccountResourceType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeSite
import com.morpheusdata.model.MetadataTag
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
import io.reactivex.Completable
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
	protected Map<String, List<AccountResourceType>> resourceTypes
	protected List<ComputeSite> sites

	static String interval='month'
	static Map<String,Object> compatibleReportConfig = [timeUnit:'HOURLY', format:'textORcsv', compresssion:'GZIP', extraElements:new ArrayList<String>(['RESOURCES']),
														refreshClosed:true, versioning:'CREATE_NEW_REPORT']
	static String billingDateFormat = 'yyyyMMdd'
	static String billingPeriodFormat = "yyyyMMdd'T'HHmmss.SSS'Z'"
	static Integer billingBatchSize = 10000
	static String dateIntervalFormat = 'yyyy-MM-dd'

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
			morpheus.async.cloud.updateCloudCostStatus(cloud, Cloud.Status.syncing, null, null)

			if(initializeCosting(cloud).success) {
				if (cloud.costingMode in ['full', 'costing']) {
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
					processAwsBillingFiles(cloud, processReport, costDate)
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
	void processAwsBillingFiles(Cloud cloud, Map costReport, Date costDate) {
		try {
			Map opts = [cloudInvoices: [:]]
			Date startDate = new Date()
			Integer batchIndex = 1, lineCount = 0L
			Integer reportIndex
			String manifestJson = (costReport.manifestFile as CloudFile).getText()
			Map manifest = new JsonSlurper().parseText(manifestJson) as Map
			log.debug("manifest: {}", manifest)
			List<String> reportKeys = manifest.reportKeys as List<String>
			Map<String, Object> billingPeriod = manifest.billingPeriod as Map<String, Object>
			Date billingStart = new Date(DateUtility.getGmtDate(DateUtility.parseDateWithFormat(billingPeriod?.start as String, billingPeriodFormat)).getTime() + (24l*60l*60000l))
			String period = InvoiceUtility.getPeriodString(billingStart)
			StorageProvider storageProvider = ((StorageProvider) (costReport.provider))
			log.info("Processing Files for Billing Period {} - File Count: {}", period, reportKeys.size())
			Observable<String> reportObservable = Observable.fromIterable(reportKeys)
			reportObservable.concatMap { String reportKey ->
				createCsvStreamer(storageProvider,costReport.bucket as String,reportKey,period,manifest)
			}.buffer(billingBatchSize).map {rows ->
				Date batchStart = new Date()
				InvoiceProcessResult batchResult = processAwsBillingBatch(cloud,period,rows,costDate,opts)
				lineCount += rows.size()
				log.info("Processed {} Records of Batch {} for Billing Period {} in {}ms", rows.size(), batchIndex++, period, new Date().time - batchStart.time)
				batchResult
			}.buffer(billingBatchSize * 1000).concatMapCompletable {List<InvoiceProcessResult> processResults ->
				List<Long> invoiceIds = processResults.collect { it.invoicesToReconcile }.flatten().unique()
				Collection<String> cloudUUIDs = processResults.collect { it.usageClouds }.flatten().unique()
				morpheusContext.async.costing.invoice.bulkReconcileInvoices(invoiceIds).blockingGet()
				morpheusContext.async.costing.invoice.summarizeCloudInvoice(cloud, period, costDate, cloudUUIDs).blockingGet()
				morpheusContext.async.costing.invoice.processProjectedCosts(cloud, period, cloudUUIDs).blockingGet()
				Completable.complete()
			}.blockingAwait()
			log.info("Processed Files for Cloud {} Billing Period {} Modified On {} With {} Batches {} Records In {} seconds",
				cloud.name, period, costReport.lastModified, batchIndex, lineCount, ((new Date().time - startDate.time) / 1000))
		} catch(ex) {
			log.error("Error processing aws billing report file {}",ex.message,ex)
		}
	}

	InvoiceProcessResult processAwsBillingBatch(Cloud cloud, String tmpPeriod, Collection<Map<String,Object>> lineItems, Date costDate, Map opts) {
		InvoiceProcessResult rtn = new InvoiceProcessResult()
		try {
			Date startDate = DateUtility.getStartOfGmtMonth(costDate)
			Date endDate = DateUtility.getEndOfGmtMonth(costDate)
			List<String> lineItemIds = [] as List<String>
			List<String> usageAccountIds = [] as List<String>
			List<AccountInvoiceItem> invoiceItems

			for(li in lineItems) {
				usageAccountIds.add(li.lineItem['UsageAccountId'] as String)
				lineItemIds.add(li.uniqueId as String)
			}

			//reduce size of list for repeat entries for the same item
			usageAccountIds.unique()
			lineItemIds.unique()
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
				usageClouds = morpheusContext.services.cloud.list(new DataQuery().withFilters(queryFilters))
			}
			Map<String,List<Cloud>> usageCloudsByExternalId = (usageClouds.groupBy{it.linkedAccountId ?: it.externalId} as Map<String,List<Cloud>>)
			List<Long> usageCloudIds = usageClouds ? (List<Long>)(usageClouds.collect{ it.id }) : [] as List<Long>
			usageCloudIds.unique()

			//def invoicesByCloud = [:]
			def invoiceItemsByCloud = [:]
			if(lineItemIds) {
				//grab all invoice items we are going to need that may already exist
				invoiceItems = morpheusContext.async.costing.invoiceItem.list(new DataQuery().withFilters(
					new DataFilter("uniqueId","in",lineItemIds),
					new DataFilter("invoice.zoneId","in",usageCloudIds),
					new DataFilter("invoice.period",tmpPeriod),
					new DataFilter("invoice.interval",interval)
				)).toList().blockingGet()
				for(AccountInvoiceItem invoiceItem : invoiceItems) {
					if(!invoiceItemsByCloud[invoiceItem.invoice.cloudId]) {
						invoiceItemsByCloud[invoiceItem.invoice.cloudId] = [:]
					}
					invoiceItemsByCloud[invoiceItem.invoice.cloudId][invoiceItem.uniqueId] = invoiceItem
				}

				def invoiceItemsByUniqueId = invoiceItems.groupBy{ it.uniqueId }
				def lineItemsToUse = []
				def invoiceItemsToUse = []

				//Filter out line items and invoice items that have already been previously processed to improve
				//performance
				for(li2 in lineItems) {
					if(!li2.product['region']) {
						li2.product['region'] = 'global' //alias no region costs to global for now
					}
					String rowId = li2.uniqueId
					BigDecimal itemRate = MorpheusUtils.parseStringBigDecimal(li2.lineItem['UnblendedRate'])
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
					} else if(clouds && clouds.collect{it.id}.intersect(invoiceItemsMatched?.collect{it.invoice.cloudId}?.unique() ?: []).size() != clouds.size()) {
						lineItemsToUse << li2
						invoiceItemsToUse += invoiceItemsMatched
					}
				}
				lineItems = lineItemsToUse

				List<String> resourceIds = [] as List<String>
				for(row in lineItems) {
					def usageAccountId = row.lineItem['UsageAccountId']
					def targetZones = usageCloudsByExternalId[usageAccountId]?.findAll{zn -> !zn.regionCode || (AmazonComputeUtility.getAmazonEndpointRegion(zn.regionCode) == row.product['region'])}
					for(zn in targetZones) {
						def itemMatch = invoiceItemsByCloud[zn.id] ? invoiceItemsByCloud[zn.id][row.uniqueId] : null
						if(!itemMatch) {
							resourceIds.add(row.lineItem['ResourceId'] as String)
							break
						}
					}
				}

				//We filter out only ids related to compute server record types in amazon. This could change in future for RDS
				//TODO: Factor in RDS IDS
				def serverResourceIds = []
				def volumeResourceIds = []
				def snapshotResourceIds = []
				def loadBalancerResourceIds = []
				def virtualImageResourceIds = []
				def resourceAccounts = [:]
				def resourcesByCloud = [:]
				def emptyInvoicesByCloud = [:]

				if(resourceIds) {
					morpheusContext.async.cloud.resource.list(new DataQuery().withFilters(
						new DataFilter("zoneId","in",usageCloudIds),
						new DataFilter("externalId","in",resourceIds)
					)).blockingSubscribe {
						if(!resourcesByCloud[it.cloudId]) {
							resourcesByCloud[it.cloudId] = [:] as Map<String, AccountResource>
						}
						resourcesByCloud[it.cloudId][it.externalId] = it
					}

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

					morpheusContext.async.costing.invoice.list(
						new DataQuery().withFilters([
						    new DataFilter('resourceExternalId', 'in', resourceIds),
							new DataFilter('zoneId', 'in', usageCloudIds),
							new DataFilter('period', tmpPeriod),
							new DataFilter('interval', interval),
							new DataFilter('lineItems', 'empty', true)
						])
					).blockingSubscribe {
						if(!emptyInvoicesByCloud[it.cloudId]) {
							emptyInvoicesByCloud[it.cloudId] = [:] as Map<String, AccountInvoice>
						}
						emptyInvoicesByCloud[it.cloudId][it.resourceExternalId] = it
					}
				}

				def serversByCloud = [:]
				def volumeServersByCloud = [:]
				if(serverResourceIds) {
					//we need to look for any possible matching server records
					List<ComputeServer> serverList = morpheusContext.services.computeServer.list(new DataQuery().withFilters(
						new DataFilter("zone.id","in",usageCloudIds),
						new DataFilter("externalId","in",serverResourceIds)
					))

					if(volumeResourceIds) {
						morpheusContext.async.computeServer.list(new DataQuery().withFilters(
							new DataFilter("zone.id","in",usageCloudIds),
							new DataFilter("volumes.externalId","in",volumeResourceIds)
						)).blockingSubscribe {
							if(!volumeServersByCloud[it.cloud.id]) {
								volumeServersByCloud[it.cloud.id] = [:] as Map<Long, ComputeServer>
							}
							for(StorageVolume volume : it.volumes) {
								volumeServersByCloud[it.cloud.id][volume.externalId] = it
							}
							serverList << it
						}
					}
					for(ComputeServer server : serverList) {
						resourceAccounts[server.account.id] = server.account

						if(!serversByCloud[server.cloud.id]) {
							serversByCloud[server.cloud.id] = [:] as Map<String, ComputeServer>
						}
						serversByCloud[server.cloud.id][server.externalId] = server
					}
				}

				def volumesByCloud = [:]
				if(volumeResourceIds) {
					//load storage volume objects
					def volumeList = morpheusContext.services.storageVolume.list(new DataQuery().withFilters(
						 new DataFilter('cloudId', 'in', usageCloudIds),
						 new DataFilter('externalId', 'in', volumeResourceIds)
					))
					for(StorageVolume volume : volumeList) {
						if(!volumesByCloud[volume.cloudId]) {
							volumesByCloud[volume.cloudId] = [:] as Map<String, StorageVolume>
						}
						volumesByCloud[volume.cloudId][volume.externalId] = volume
					}
				}

				def lbsByCloud = [:]
				if(loadBalancerResourceIds) {
					//load lbs
					morpheusContext.async.loadBalancer.list(new DataQuery().withFilters(
						new DataFilter('cloud.id', 'in', usageCloudIds),
						new DataFilter('externalId', 'in', loadBalancerResourceIds)
					)).blockingSubscribe {
						if(!lbsByCloud[it.cloud.id]) {
							lbsByCloud[it.cloud.id] = [:] as Map<String, NetworkLoadBalancer>
						}
						lbsByCloud[it.cloud.id][it.externalId] = it
						resourceAccounts[it.account.id] = it.account
					}
				}

				List<AccountResource> newResources = [] as List<AccountResource>
				Map<String,AccountInvoiceItem> invoiceItemSaves = [:] as Map<String, AccountInvoiceItem>
				Map<String,List<Map<String,Object>>> usageAccountLineItems = (lineItems.groupBy{ r->r.lineItem['UsageAccountId']} as Map<String,List<Map<String,Object>>>)

				//get existing invoices by refType
				DataOrFilter refTypeFilters = new DataOrFilter()
				List<Long> serverRefIds = serversByCloud.values().collect { it.values() }.flatten().collect { it.id }
				List<Long> lbRefIds = lbsByCloud.values().collect { it.values() }.flatten().collect { it.id }
				List<Long> volumeRefIds = volumesByCloud.values().collect { it.values() }.flatten().collect { it.id }
				List<Long> resourceRefIds = resourcesByCloud.values().collect { it.values() }.flatten().collect { it.id }

				if(serverRefIds) {
					refTypeFilters.withFilter(new DataAndFilter([new DataFilter('refType', 'ComputeServer'), new DataFilter('refId', 'in', serverRefIds)]))
				}
				if(lbRefIds) {
					refTypeFilters.withFilters(new DataAndFilter([new DataFilter('refType', 'NetworkLoadBalancer'), new DataFilter('refId', 'in', lbRefIds)]))
				}
				if(volumeRefIds) {
					refTypeFilters.withFilters(new DataAndFilter([new DataFilter('refType', 'StorageVolume'), new DataFilter('refId', 'in', volumeRefIds)]))
				}
				if(resourceRefIds) {
					refTypeFilters.withFilters(new DataAndFilter([new DataFilter('refType', 'AccountResource'), new DataFilter('refId', 'in', resourceRefIds)]))
				}

				def groupedInvoicesByCloud = [:]
				if(serverRefIds || lbRefIds || volumeRefIds || resourceRefIds) {
					morpheusContext.async.costing.invoice.list(
						new DataQuery().withFilters([
						    new DataFilter('period', tmpPeriod),
							new DataFilter('interval', interval),
							refTypeFilters
						])
					).blockingSubscribe {
						if(!groupedInvoicesByCloud[it.cloudId]) {
							groupedInvoicesByCloud[it.cloudId] = [
							    serverInvoices: [:],
								lbInvoices: [:],
								volumeInvoices: [:],
								resourceInvoices: [:]
							]
						}
						if(it.refType == 'ComputeServer') {
							groupedInvoicesByCloud[it.cloudId].serverInvoices[it.resourceExternalId] = it
						}
						if(it.refType == 'NetworkLoadBalancer') {
							groupedInvoicesByCloud[it.cloudId].lbInvoices[it.resourceExternalId] = it
						}
						if(it.refType == 'StorageVolume') {
							groupedInvoicesByCloud[it.cloudId].volumeInvoices[it.resourceExternalId] = it
						}
						if(it.refType == 'AccountResource') {
							groupedInvoicesByCloud[it.cloudId].resourceInvoices[it.resourceExternalId] = it
						}
					}
				}

				for(usageAccountId in usageAccountLineItems.keySet()) {
					List<Cloud> targetClouds = usageCloudsByExternalId[usageAccountId]
					for(Cloud targetCloud in targetClouds) {
						AccountInvoice cloudInvoice = opts.cloudInvoices[targetCloud.id]

						if(!cloudInvoice) {
							cloudInvoice = morpheusContext.async.costing.invoice.find(
								new DataQuery().withFilters([
									new DataFilter('refType', 'ComputeZone'),
									new DataFilter('refId', targetCloud.id),
									new DataFilter('period', tmpPeriod),
									new DataFilter('interval', 'month')
								])
							).blockingGet()
							opts.cloudInvoices[targetCloud.id] = cloudInvoice
						}

						ComputeSite cloudSite = allSites.find { it.account.id == targetCloud.account.id } ?: allSites.first()

						// resources
						def cloudResources = resourcesByCloud[targetCloud.id] ?: [:]
						def cloudServers = serversByCloud[targetCloud.id] ?: [:]
						def cloudVolumes = volumesByCloud[targetCloud.id] ?: [:]
						def cloudVolumeServers = volumeServersByCloud[targetCloud.id] ?: [:]
						def cloudLbs = lbsByCloud[targetCloud.id] ?: [:]
						// invoices
						def cloudEmptyInvoices = emptyInvoicesByCloud[targetCloud.id] ?: [:]
						def cloudLbInvoices = groupedInvoicesByCloud[targetCloud.id]?.lbInvoices ?: [:]
						def cloudServerInvoices = groupedInvoicesByCloud[targetCloud.id]?.serverInvoices ?: [:]
						def cloudVolumeInvoices = groupedInvoicesByCloud[targetCloud.id]?.volumeInvoices ?: [:]
						def cloudResourceInvoices = groupedInvoicesByCloud[targetCloud.id]?.resourceInvoices ?: [:]
						def cloudInvoiceItems = invoiceItemsByCloud[targetCloud.id] ?: [:]

						//process line items
						LinkedList<Map<String,Object>> regionLineItems = (usageAccountLineItems[usageAccountId].findAll{ tmpRow -> (tmpRow.product['region'] && (!targetCloud.regionCode || (AmazonComputeUtility.getAmazonEndpointRegion(targetCloud.regionCode) == tmpRow.product['region']) || (!tmpRow.product['region'] && targetCloud.regionCode == 'global')))} as LinkedList<Map<String,Object>>)

						for(Map<String,Object> row in regionLineItems) {
							//Boolean newResourceMade = false
							String rowId = row.uniqueId
							def resourceId = row.lineItem['ResourceId']
							def lineItemId = row.identity['LineItemId']
							Date lineItemStartDate = row.lineItem['UsageStartDate'] instanceof String ? DateUtility.parseDate(row.lineItem['UsageStartDate'] as String) : row.lineItem['UsageStartDate'] as Date
							Date lineItemEndDate = row.lineItem['UsageEndDate'] instanceof String ? DateUtility.parseDate(row.lineItem['UsageEndDate'] as String) : row.lineItem['UsageEndDate'] as Date
							def volMatch = null
							def tags = row.resourceTags?.collect { [name: it.key.startsWith('user:') ? it.key.substring(5) : it.key, value: it.value]}?.findAll{it.value}
							//find the line item
							AccountInvoiceItem itemMatch = cloudInvoiceItems[rowId]

							//if item match - just update info
							if(itemMatch) {
								if(lineItemStartDate.getDate() == 1 && lineItemStartDate.getHours() == 0) {
									lineItemStartDate = lineItemStartDate
								}
								if(!InvoiceUtility.checkDateCheckHash(startDate, lineItemEndDate, itemMatch.dateCheckHash)) {
									//do an update
									def itemConfig = [
										startDate:lineItemStartDate, endDate:lineItemEndDate,
										itemUsage:row.lineItem['UsageAmount'],
										itemRate:MorpheusUtils.parseStringBigDecimal(row.lineItem['UnblendedRate']) ?: 0.0G,
										itemCost:row.lineItem['UnblendedCost'], itemPrice:row.lineItem['UnblendedCost'],
										onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? row.pricing['publicOnDemandCost'] : 0.0,
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
									updateAccountInvoiceItem(itemMatch, itemConfig)

									itemMatch.dateCheckHash = InvoiceUtility.updateDateCheckHash(startDate, lineItemEndDate, itemMatch.dateCheckHash)
									invoiceItemSaves[rowId] = itemMatch
								}
							} else {
								//find an invoice match
								AccountInvoice invoiceMatch = cloudResourceInvoices[resourceId] ?: cloudEmptyInvoices[resourceId]

								if(!invoiceMatch) {
									def resourceMatch = cloudResources[resourceId]
									ComputeServer serverMatch = null

									//if no match. - check for server
									if(!resourceMatch) {
										//check for a server, instance, container
										serverMatch = cloudServers[resourceId]
										if(serverMatch) {
											invoiceMatch = cloudServerInvoices[serverMatch.externalId]
										}
										//check for volume - add it to its server
										if(!serverMatch) {
											resourceMatch = cloudVolumes[resourceId]
											if(resourceMatch) {
												//get what its attached to?
												//create a resource for the volume?
												volMatch = resourceMatch
												serverMatch = cloudVolumeServers[resourceMatch.externalId]
												if(serverMatch) {
													invoiceMatch = cloudServerInvoices[serverMatch.externalId]
												} else {
													invoiceMatch = cloudVolumeInvoices[resourceMatch.externalId]
												}
											} else {
												//lb lookup
												resourceMatch = cloudLbs[resourceId]
												if(resourceMatch) {
													invoiceMatch = cloudLbInvoices(resourceId)
												}
											}
										}
										//add instance / container lookups?
									}
									if(!resourceMatch && !serverMatch ) {
										if(resourceId) {
											resourceMatch = createResource(targetCloud, cloudSite, row)
											if(resourceMatch) {
												cloudResources[resourceId] = resourceMatch
												newResources << resourceMatch
											} else {
												//can assign the item to the overall zone invoice
												invoiceMatch = cloudInvoice
												resourceMatch = targetCloud
											}
										} else {
											invoiceMatch = cloudInvoice
											resourceMatch = targetCloud
										}

									}
									//if still no invoice - create one
									if(!invoiceMatch) {
										if(resourceMatch || serverMatch) {
											invoiceMatch = new AccountInvoice(
												estimate: false, startDate: startDate, endDate: endDate, powerDate: startDate,
												refStart: startDate, refEnd: endDate, refCategory:'invoice', period: tmpPeriod,
												accountId: cloud.account.id, accountName: cloud.account.name,
												ownerId: (cloud.owner ?: cloud.account).id, ownerName: (cloud.owner ?: cloud.account).name,
												cloudId: targetCloud.id, cloudRegion: targetCloud.regionCode, cloudName: targetCloud.name, cloudUUID: targetCloud.uuid
											)

											if(resourceMatch instanceof AccountResource) {
												InvoiceUtility.configureResourceInvoice(invoiceMatch, targetCloud, resourceMatch)
												cloudResourceInvoices[resourceId] = invoiceMatch
											}
											else if(resourceMatch instanceof ComputeServer) {
												InvoiceUtility.configureServerInvoice(invoiceMatch, targetCloud, resourceMatch)
												cloudResourceInvoices[resourceId] = invoiceMatch
											}
											else if(resourceMatch instanceof Cloud) {
												InvoiceUtility.configureCloudInvoice(invoiceMatch, targetCloud, cloudSite)
												cloudInvoice = invoiceMatch
											} else if(resourceMatch instanceof NetworkLoadBalancer) {
												InvoiceUtility.configureLoadBalancerInvoice(invoiceMatch, targetCloud, resourceMatch)
												cloudResourceInvoices[resourceId] = invoiceMatch
											} else if(serverMatch) {
												InvoiceUtility.configureServerInvoice(invoiceMatch, targetCloud, serverMatch)
												cloudResourceInvoices[resourceId] = invoiceMatch
											} else if(resourceMatch instanceof StorageVolume) {
												InvoiceUtility.configureVolumeInvoice(invoiceMatch, targetCloud, resourceMatch)
												cloudResourceInvoices[resourceId] = invoiceMatch
											}

											//create it
											invoiceMatch.metadata = tags?.collect { new MetadataTag(name: it.name, value: it.value)}
										} else {
											log.warn("Unprocessed things")
											//shouldn't get here ever
										}
									}
								}

								//if we have an invoice
								if(invoiceMatch) {
									def category = categoryForInvoiceItem(row.product['servicecode'],row.lineItem['UsageType'])
									//create the line item
									BigDecimal unblendedRate = MorpheusUtils.parseStringBigDecimal(row.lineItem['UnblendedRate'])
									BigDecimal unblendedCost = row.lineItem['UnblendedCost']
									AccountInvoiceItem invoiceItem = new AccountInvoiceItem(
										invoice: invoiceMatch, refCategory:'invoice',
										refType: volMatch ? 'StorageVolume' : invoiceMatch.refType, refId: volMatch?.id ?: invoiceMatch.refId,
										refName: volMatch?.name ?: invoiceMatch.refName, itemName: volMatch?.name ?: invoiceMatch.refName,
										startDate:lineItemStartDate, endDate:lineItemEndDate, itemId:lineItemId, itemType:row.lineItem['LineItemType'],
										itemDescription:row.lineItem['LineItemDescription'], externalId: lineItemId,
										productCode:row.lineItem['ProductCode'], productName:row.lineItem['ProductName'],
										itemSeller:row.lineItem['LegalEntity'], itemAction:row.lineItem['Operation'], usageType:row.lineItem['UsageType'],
										usageService:row.product['servicecode'], rateId:row.pricing['RateId'], rateClass:row.lineItem['RateClass'],
										rateUnit:row.pricing['unit'], rateTerm:row.pricing['LeaseContractLength'], itemUsage:row.lineItem['UsageAmount'],
										itemRate:unblendedRate, itemCost:unblendedCost, itemPrice:unblendedCost,
										onDemandCost:row.lineItem['LineItemType'] != 'SavingsPlanNegation' ? row.pricing['publicOnDemandCost'] : 0.0,
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
										regionCode: row.product['region'], lastInvoiceSyncTimestamp: costDate.getTime(),
										dateCheckHash: InvoiceUtility.updateDateCheckHash(startDate, lineItemEndDate, null)
									)

									// map invoice item to invoice via resourceId
									invoiceItemSaves[rowId] = invoiceItem
									cloudInvoiceItems[rowId] = invoiceItem
									//done
								}
							}
						}
					}
				}

				List<AccountInvoiceItem> invoiceItemCreates = invoiceItemSaves.values().findAll { !it.id }
				List<AccountInvoiceItem> invoiceItemUpdates = invoiceItemSaves.values().findAll { it.id }

				if(newResources) {
					BulkCreateResult<AccountResource> createResult = morpheusContext.services.cloud.resource.bulkCreate(newResources)
					Map<String, AccountInvoice> createdResources = [:]
					for(AccountResource resource : createResult.persistedItems) {
						createdResources[resource.externalId] = resource
					}
					if(createResult.failedItems) {
						log.error("Unable to create {} invoices due to error: {}", createResult.failedItems.size(), createResult.msg)
					}
					//associate new resources w/ invoices
					for(AccountInvoiceItem invoiceItem : invoiceItemCreates) {
						if(!invoiceItem.refId && invoiceItem.refType == 'AccountResource') {
							invoiceItem.invoice.refId = invoiceItem.invoice.refId ?: createdResources[invoiceItem.invoice.resourceExternalId]?.id
							invoiceItem.refId = invoiceItem.invoice.refId
						}
					}
				}

				if(invoiceItemCreates) {
					// create new invoices first
					List<AccountInvoice> newInvoices = invoiceItemCreates.findAll {!it.invoice.id }.collect { it.invoice }.unique { it.resourceExternalId }

					if(newInvoices) {
						BulkCreateResult<AccountInvoice> createResult = morpheusContext.async.costing.invoice.bulkCreate(newInvoices).blockingGet()
						Map<String, AccountInvoice> createdInvoices = [:]
						for(AccountInvoice invoice : createResult.persistedItems) {
							createdInvoices[invoice.resourceExternalId] = invoice
						}
						if(createResult.failedItems) {
							log.error("Unable to create {} invoices due to error: {}", createResult.failedItems.size(), createResult.msg)
						}
						// associate new invoices w/ invoice items
						for(AccountInvoiceItem invoiceItem : invoiceItemCreates) {
							if(!invoiceItem.invoice.id) {
								if(!createdInvoices[invoiceItem.invoice.resourceExternalId]) {
									log.error("unable to find invoice for {}", invoiceItem.invoice.resourceExternalId )
								}
								invoiceItem.invoice = createdInvoices[invoiceItem.invoice.resourceExternalId]
							}
						}
					}
					// create invoice items:
					BulkCreateResult<AccountInvoiceItem> createResult = morpheusContext.async.costing.invoiceItem.bulkCreate(invoiceItemCreates).blockingGet()
					for(AccountInvoiceItem invoiceItem : createResult.persistedItems) {
						rtn.invoicesToReconcile << invoiceItem.invoice.id
						rtn.usageClouds << invoiceItem.invoice.cloudUUID
					}
					if(createResult.failedItems) {
						log.error("Unable to create {} invoice items due to error: {}", createResult.failedItems.size(), createResult.msg)
					}
				}

				if(invoiceItemUpdates) {
					BulkSaveResult<AccountInvoiceItem> saveResult = morpheusContext.async.costing.invoiceItem.bulkSave(invoiceItemUpdates).blockingGet()
					for(AccountInvoiceItem invoiceItem : saveResult.persistedItems) {
						rtn.invoicesToReconcile << invoiceItem.invoice.id
						rtn.usageClouds << invoiceItem.invoice.cloudUUID
					}
					if(saveResult.failedItems) {
						log.error("Unable to save {} invoices due to error: {}", saveResult.failedItems.size(), saveResult.msg)
					}
				}
			}
		} catch(e) {
			log.error("Error Occurred Processing Batched set of Line Items {}",e.message,e)
		}
		rtn
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
					List columnList = []
					Long lineCounter = 0
					Integer costColumnIdx
					for(CsvRow line : csvReader) {
						if(lineCounter == 0) {
							//process the header
							columnList = processBillingHeader(manifest, line)
							columnList.eachWithIndex{ column, idx ->
								if(column.name == 'UnblendedCost') {
									costColumnIdx = idx
								}
							}
							log.debug("columnList: {}", columnList)
						} else if(line.fields && MorpheusUtils.parseStringBigDecimal(line.fields[costColumnIdx])) {
							Map<String, Object> row = processBillingLine(columnList, line)
							if(row?.lineItem) {
								row.uniqueId = getLineItemHash("${row.lineItem['UsageAccountId']}_${row.product['servicecode']}_${row.lineItem['UsageType']}_${row.lineItem['Operation']}_${row.lineItem['LineItemType']}_${row.lineItem['UnblendedRate']}_${row.lineItem['ResourceId']}_${row.savingsPlan['SavingsPlanARN']}_${row.lineItem['AvailabilityZone']}_${row.reservation['SubscriptionId']}_${row.lineItem['LineItemDescription']}")
								emitter.onNext(row)
							}
						}
						lineCounter++
					}
					emitter.onComplete()
				} catch(Exception e) {
					emitter.onError(e)
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

	private static getAmortizedCost(row) {
		def cost
		switch (row.lineItem['LineItemType']) {
			case 'SavingsPlanCoveredUsage':
				cost = MorpheusUtils.parseStringBigDecimal(row.savingsPlan['SavingsPlanEffectiveCost'])
				break
			case 'SavingsPlanRecurringFee':
				cost = MorpheusUtils.parseStringBigDecimal(row.savingsPlan['TotalCommitmentToDate']) - MorpheusUtils.parseStringBigDecimal(row.savingsPlan['UsedCommitment'])
				break
			case 'SavingsPlanNegation':
			case 'SavingsPlanUpfrontFee':
				cost = 0.0
				break
			case 'DiscountedUsage':
				cost = MorpheusUtils.parseStringBigDecimal(row.reservation['EffectiveCost'])
				break
			case 'RIFee':
				cost = MorpheusUtils.parseStringBigDecimal(row.reservation['UnusedAmortizedUpfrontFeeForBillingPeriod']) + MorpheusUtils.parseStringBigDecimal(row.reservation['UnusedRecurringFee'])
				break
			default:
				if (row.lineItem['LineItemType'] == 'Fee' && row.reservation['ReservationARN']) {
					cost = 0.0
				} else {
					cost = row.lineItem['UnblendedCost']
				}
		}

		return cost
	}

	protected Boolean checkDateCheckHash(Date billingStartDate, Date lineItemDate, String existingHash) {
		Byte[] hashArray
		if(existingHash) {
			hashArray = existingHash.decodeHex()
		} else {
			return false
		}

		Integer currentHour = ((lineItemDate.time - billingStartDate.time) / 3600000L ) as Integer
		Integer hourByteIndex = (currentHour / 8L) as Integer
		Integer bitOffset = currentHour % 8

		Byte hourByte = hashArray[hourByteIndex]
		if(hourByte!= null && (hourByte & (((0x01 as byte) << bitOffset) as byte)) != 0) {
			return true
		} else {
			return false
		}
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
				def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
				def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
				log.debug("identityResults: {}", identityResults.results)
				def awsAccountId = identityResults.results?.getAccount()
				def awsUserArn = identityResults.results?.getArn()
				def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud)
				def awsRegions = morpheusContext.async.cloud.region.list(new DataQuery().withFilter(new DataFilter<Long>("zone.id",cloud.id))).map {it.externalId}.toList().blockingGet()
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

	def loadAwsServiceUsage(Cloud cloud, Date costDate) {
		def rtn = [success:false, services:[:], metrics:[:], cost:0]
		try {
			costDate = costDate ?: new Date()
			Date periodStart = InvoiceUtility.getPeriodStart(costDate)
			Date periodEnd = InvoiceUtility.getPeriodEnd(costDate)
			//get identity info
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud)
			def awsRegions = morpheusContext.async.cloud.region.list(new DataQuery().withFilter(new DataFilter<Long>("zone.id",cloud.id))).map {it.externalId}.toList().blockingGet()
			def isGovAccount = awsUserArn?.indexOf('-gov') > -1
			log.debug("awsRegion: {} awsAccountId: {} awsUserArn: {} isGovAccount: {}", awsRegion, awsAccountId, awsUserArn, isGovAccount)
			//get client
			AWSCostExplorer amazonClient = getAmazonCostClient(cloud)
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
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud)
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
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
			def identityResults = AmazonComputeUtility.getClientIdentity(securityClient, [:])
			log.debug("identityResults: {}", identityResults.results)
			def awsAccountId = identityResults.results?.getAccount()
			def awsUserArn = identityResults.results?.getArn()
			def awsRegion = AmazonComputeUtility.getAmazonEndpointRegion(cloud)
			def awsRegions = morpheusContext.async.cloud.region.list(new DataQuery().withFilter(new DataFilter<Long>("zone.id",cloud.id))).map {it.externalId}.toList().blockingGet()
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
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(cloud)
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
			def securityClient = AmazonComputeUtility.getAmazonCostingSecurityClient(zone)
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

	def findBillingService(String groupKey) {
		def rtn = awsServicesList.find{ it.name == groupKey }
		if(rtn == null) {
			rtn = awsServicesList.find{ groupKey.startsWith(it.name) }
			if(rtn == null) {
				rtn = awsServicesList.find{ it.code == groupKey }
				if(rtn == null) {
					rtn = awsServicesList.find{ groupKey.startsWith(it.code) }
					if(rtn == null) {
						//need to find the start now
						def regionDash = groupKey.indexOf('-')
						if(regionDash > -1) {
							def testKey = groupKey.substring(regionDash)
							rtn = awsServicesList.find{ testKey.startsWith(it.name) }
							if(rtn == null) {
								testKey = groupKey.substring(regionDash + 1)
								rtn = awsServicesList.find{ testKey.startsWith(it.name) }
							}
						}
					}
				}
			}
		}
		if(rtn == null)
			rtn = awsServicesList[0] //generic
		return rtn
	}

	def findBillingCategory(String groupKey) {
		def rtn = costCategories.find{ it.key == groupKey }
		if(rtn == null) {
			rtn = costCategories.find{ groupKey.endsWith(it.key) }
			if(rtn == null) {
				//need to find the start now
				def regionDash = groupKey.indexOf('-')
				if(regionDash > -1) {
					def testKey = groupKey.substring(regionDash)
					rtn = costCategories.find{ testKey.startsWith(it.key) }
					if(rtn == null) {
						testKey = groupKey.substring(regionDash + 1)
						rtn = costCategories.find{ testKey.startsWith(it.key) }
					}
				} else {
					rtn = costCategories.find{ groupKey.startsWith(it.key) }
				}
			}
		}
		if(rtn == null)
			rtn = costCategories[0] //generic
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
			def reportResults = loadAwsReportDefinitions(cloud)

			if(reportResults.success) {
				reportResults.reports?.each { report ->
					if (report.reportName == costingReport /*&& report.getS3Bucket() == costingBucket*/) {
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

	private createResource(Cloud cloud, ComputeSite site, Map lineItem) {
		AccountResource rtn
		def costingCode = lineItem.lineItem['ProductCode']
		def costingType = lineItem.lineItem['UsageType']
		//find the type
		def typeMatch = findResourceType(costingCode, costingType)
		if (typeMatch) {
			def resourceId = lineItem.lineItem['ResourceId']
			def resourceIndex = resourceId.lastIndexOf(':')
			def resourceName
			if (lineItem.resourceTags) {
				resourceName = lineItem.resourceTags['user:Name']
			}
			if (!resourceName) {
				resourceName = resourceIndex > -1 ? resourceId.substring(resourceIndex) : null
			}
			//set the name to resource id if not found?
			if (!resourceName)
				resourceName = resourceId
			if (!resourceName)
				resourceName = costingCode + ' ' + costingType

			def tags = lineItem.resourceTags?.collect { [name: (it.key.startsWith('user:') ? it.key.substring(5) : it.key), value: it.value] }

			rtn = new AccountResource(
				owner: cloud.owner, name: resourceName, displayName: resourceName, enabled: typeMatch.enabled,
				resourceType: (costingCode + '.' + costingType), cloudId: cloud.id, cloudName: cloud.name,
				externalId: resourceId, type: typeMatch, siteId: site?.id, siteName: site?.name,
				metadata: tags?.collect { new MetadataTag(name: it.name, value: it.value)},
				status: 'running', rawData: lineItem?.encodeAsJSON().toString()
			)
		}
		rtn
	}

	private updateAccountInvoiceItem(AccountInvoiceItem lineItem, Map config) {
		//new end date
		if((config.endDate as Date) > lineItem.endDate)
			lineItem.endDate = config.endDate
		if((config.startDate as Date) < lineItem.startDate)
			lineItem.startDate = config.startDate
		//update usage
		if(config.itemUsage != null)
			lineItem.itemUsage = (lineItem.itemUsage ?: 0) + config.itemUsage
		//update cost
		if(config.itemCost != null) {
			lineItem.itemCost = (lineItem.itemCost ?: 0) + config.itemCost
			if(config.appendPrice == true) {
				//appears some cost services overwrite the itemPrice. Let's allow option to append
				lineItem.itemPrice = (lineItem.itemPrice ?: 0) + (config.itemPrice ?: config.itemCost)
			} else {
				lineItem.itemPrice = lineItem.itemCost
			}
		}
		if(config.onDemandCost != null)
			lineItem.onDemandCost = (lineItem.onDemandCost ?: 0) + config.onDemandCost
		if(config.amortizedCost != null)
			lineItem.amortizedCost = (lineItem.amortizedCost ?: 0) + config.amortizedCost
		//update other properties if they have changed
		if(config.refName && lineItem.refName != config.refName)
			lineItem.refName = config.refName
		if(config.refCategory && lineItem.refCategory != config.refCategory)
			lineItem.refCategory = config.refCategory
		if(config.itemId && lineItem.itemId != config.itemId)
			lineItem.itemId = config.itemId
		if(config.itemType && lineItem.itemType != config.itemType)
			lineItem.itemType = config.itemType
		if(config.itemName && lineItem.itemName != config.itemName)
			lineItem.itemName = config.itemName
		if(config.itemDescription && lineItem.itemDescription != config.itemDescription)
			lineItem.itemDescription = config.itemDescription
		if(config.productId && lineItem.productId != config.productId)
			lineItem.productId = config.productId
		if(config.productCode && lineItem.productCode != config.productCode)
			lineItem.productCode = config.productCode
		if(config.procutName && lineItem.productName != config.productName)
			lineItem.productName = config.productName
		if(config.itemSeller && lineItem.itemSeller != config.itemSeller)
			lineItem.itemSeller = config.itemSeller
		if(config.itemAction && lineItem.itemAction != config.itemAction)
			lineItem.itemAction = config.itemAction
		if(config.usageType && lineItem.usageType != config.usageType)
			lineItem.usageType = config.usageType
		if(config.usageCategory && lineItem.usageCategory != config.usageCategory)
			lineItem.usageCategory = config.usageCategory
		if(config.usageService && lineItem.usageService != config.usageService)
			lineItem.usageService = config.usageService
		if(config.rateId && lineItem.rateId != config.rateId)
			lineItem.rateId = config.rateId
		if(config.rateClass && lineItem.rateClass != config.rateClass)
			lineItem.rateClass = config.rateClass
		if(config.rateUnit && lineItem.rateUnit != config.rateUnit)
			lineItem.rateUnit = config.rateUnit
		if(config.rateTerm && lineItem.rateTerm != config.rateTerm)
			lineItem.rateTerm = config.rateTerm
		if(config.itemRate != null && lineItem.itemRate != config.itemRate)
			lineItem.itemRate = config.itemRate
		if(config.itemTax != null && lineItem.itemTax != config.itemTax)
			lineItem.itemTax = config.itemTax
		if(config.itemTerm && lineItem.itemTerm != config.itemTerm)
			lineItem.itemTerm = config.itemTerm
		if(config.taxType && lineItem.taxType != config.taxType)
			lineItem.taxType = config.taxType
		if(config.costProject && lineItem.costProject != config.costProject)
			lineItem.costProject = config.costProject
		if(config.costTeam && lineItem.costTeam != config.costTeam)
			lineItem.costTeam = config.costTeam
		if(config.costEnvironment && lineItem.costEnvironment != config.costEnvironment)
			lineItem.costEnvironment = config.costEnvironment
		if(config.availabilityZone && lineItem.availabilityZone != config.availabilityZone)
			lineItem.availabilityZone = config.availabilityZone
		if(config.operatingSystem && lineItem.operatingSystem != config.operatingSystem)
			lineItem.operatingSystem = config.operatingSystem
		if(config.purchaseOption && lineItem.purchaseOption != config.purchaseOption)
			lineItem.purchaseOption = config.purchaseOption
		if(config.tenancy && lineItem.tenancy != config.tenancy)
			lineItem.tenancy = config.tenancy
		if(config.databaseEngine && lineItem.databaseEngine != config.databaseEngine)
			lineItem.databaseEngine = config.databaseEngine
		if(config.billingEntity && lineItem.billingEntity != config.billingEntity)
			lineItem.billingEntity = config.billingEntity
		if(config.regionCode && lineItem.regionCode != config.regionCode)
			lineItem.regionCode = config.regionCode
		if(config.lastInvoiceSyncDate) {
			lineItem.lastInvoiceSyncTimestamp = config.lastInvoiceSyncDate.time
		}
	}

	private List<ComputeSite> getAllSites() {
		sites ?: (sites = morpheusContext.services.computeSite.list(new DataQuery()).sort { it.id })
	}

	private Map<String, AccountResourceType> getAllResourceTypes() {
		if(!resourceTypes) {
			resourceTypes = [:]
			morpheusContext.async.accountResourceType.list(new DataQuery().withFilter('category', 'aws.cloudFormation')).blockingSubscribe {
				if(!resourceTypes[it.costingCode]) {
					resourceTypes[it.costingCode] = [] as List<AccountResourceType>
				}
				resourceTypes[it.costingCode] << it
			}
		}
		resourceTypes
	}

	private findResourceType(String costingCode, String costingType) {
		AccountResourceType rtn
		if(costingCode && costingType) {
			rtn = allResourceTypes[costingCode]?.find{it.costingType == costingType}

			if(rtn == null && costingType?.indexOf(':') > -1) {
				def searchType = costingType.substring(0, costingType.indexOf(':'))
				rtn = allResourceTypes[costingCode]?.find{it.costingType == searchType}
			}
		}
		if(rtn == null && costingCode) {
			rtn = allResourceTypes[costingCode]?.find{it.costingType == null}
		}
		rtn
	}

	protected static AWSCostExplorer getAmazonCostClient(Cloud cloud) {
		AmazonComputeUtility.getAmazonCostClient(cloud)
	}

	static awsServicesList = [
		[code:'Other', name:'Other', priceType:'other'],
		[code:'Amplify', name:'AWS Amplify', priceType:'compute'],
		[code:'AppSync', name:'AWS AppSync', priceType:'compute'],
		[code:'Backup', name:'AWS Backup', priceType:'storage'],
		[code:'Budgets', name:'AWS Budgets', priceType:'other'],
		[code:'CertificateManager', name:'AWS Certificate Manager', priceType:'compute'],
		[code:'CloudMap', name:'AWS Cloud Map', priceType:'compute'],
		[code:'CloudTrail', name:'AWS CloudTrail', priceType:'compute'],
		[code:'CodeCommit', name:'AWS Code Commit', priceType:'compute'],
		[code:'CodeDeploy', name:'AWS CodeDeploy', priceType:'compute'],
		[code:'CodePipeline', name:'AWS CodePipeline', priceType:'compute'],
		[code:'Config', name:'AWS Config', priceType:'other'],
		[code:'CostExplorer', name:'AWS Cost Explorer', priceType:'other'],
		[code:'DataSync', name:'AWS DataSync', priceType:'storage'],
		[code:'DataTransfer', name:'AWS Data Transfer', priceType:'network'],
		[code:'DatabaseMigrationSvc', name:'AWS Database Migration Service', priceType:'compute'],
		[code:'DeveloperSupport', name:'Developer Support', priceType:'other'],
		[code:'DeviceFarm', name:'AWS Device Farm', priceType:'compute'],
		[code:'DirectConnect', name:'AWS Direct Connect', priceType:'network'],
		[code:'DirectoryService', name:'AWS Directory Service', priceType:'other'],
		[code:'ElementalMediaConvert', name:'AWS Elemental MediaConvert', priceType:'other'],
		[code:'ElementalMediaLive', name:'AWS Elemental MediaLive', priceType:'other'],
		[code:'ElementalMediaPackage', name:'AWS Elemental MediaPackage', priceType:'other'],
		[code:'ElementalMediaStore', name:'AWS Elemental MediaStore', priceType:'other'],
		[code:'ElementalMediaTailor', name:'AWS Elemental MediaTailor', priceType:'other'],
		[code:'Events', name:'CloudWatch Events', priceType:'compute'],
		[code:'FMS', name:'AWS Firewall Manager', priceType:'network'],
		[code:'GlobalAccelerator', name:'AWS Global Accelerator', priceType:'other'],
		[code:'Glue', name:'AWS Glue', priceType:'compute'],
		[code:'Greengrass', name:'AWS Greengrass', priceType:'compute'],
		[code:'IoT1Click', name:'AWS IoT 1 Click', priceType:'other'],
		[code:'IoTAnalytics', name:'AWS IoT Analytics', priceType:'compute'],
		[code:'IoTThingsGraph', name:'AWS IoT Things Graph', priceType:'compute'],
		[code:'IoT', name:'AWS IoT', priceType:'compute'],
		[code:'Lambda', name:'AWS Lambda', priceType:'compute'],
		[code:'MediaConnect', name:'AWS Elemental MediaConnect', priceType:'other'],
		[code:'QueueService', name:'Amazon Simple Queue Service', priceType:'compute'],
		[code:'RoboMaker', name:'AWS RoboMaker', priceType:'compute'],
		[code:'SecretsManager', name:'AWS Secrets Manager', priceType:'compute'],
		[code:'ServiceCatalog', name:'AWS Service Catalog', priceType:'compute'],
		[code:'Shield', name:'AWS Shield', priceType:'compute'],
		[code:'StorageGatewayDeepArchive', name:'AWS Storage Gateway Deep Archive', priceType:'storage'],
		[code:'StorageGateway', name:'AWS Storage Gateway', priceType:'storage'],
		[code:'SupportBusiness', name:'AWS Business Support', priceType:'other'],
		[code:'SupportEnterprise', name:'AWS Enterprise Support', priceType:'other'],
		[code:'SystemsManager', name:'AWS Systems Manager', priceType:'compute'],
		[code:'Transfer', name:'AWS Transfer for SFTP', priceType:'network'],
		[code:'XRay', name:'AWS X-Ray', priceType:'compute'],
		[code:'AlexaTopSites', name:'Alexa Top Sites', priceType:'other'],
		[code:'AlexaWebInfoService', name:'Alexa Web Info Service', priceType:'other'],
		[code:'ApiGateway', name:'Amazon API Gateway', priceType:'compute'],
		[code:'AppStream', name:'Amazon AppStream', priceType:'compute'],
		[code:'Athena', name:'Amazon Athena', priceType:'compute'],
		[code:'ChimeBusinessCalling', name:'Amazon Chime Business Calling', priceType:'other'],
		[code:'ChimeCallMe', name:'Amazon Chime Call Me', priceType:'other'],
		[code:'ChimeDialin', name:'Amazon Chime Dialin', priceType:'other'],
		[code:'ChimeVoiceConnector', name:'Amazon Chime Voice Connector', priceType:'other'],
		[code:'Chime', name:'Amazon Chime', priceType:'other'],
		[code:'CloudDirectory', name:'Amazon Cloud Directory', priceType:'compute'],
		[code:'CloudFront', name:'Amazon CloudFront', priceType:'compute'],
		[code:'CloudSearch', name:'Amazon CloudSearch', priceType:'compute'],
		[code:'CloudWatch', name:'AmazonCloudWatch', priceType:'compute'],
		[code:'CognitoSync', name:'Amazon CognitoSync', priceType:'compute'],
		[code:'Cognito', name:'Amazon Cognito', priceType:'compute'],
		[code:'Connect', name:'Amazon Connect', priceType:'compute'],
		[code:'DAX', name:'DynamoDB Accelerator (DAX)', priceType:'compute'],
		[code:'DocDB', name:'Amazon DocumentDB (with MongoDB compatibility)', priceType:'compute'],
		[code:'DynamoDB', name:'Amazon DynamoDB', priceType:'compute'],
		[code:'EC2', name:'Amazon Elastic Compute Cloud', priceType:'compute'],
		[code:'ECR', name:'Amazon EC2 Container Registry (ECR)', priceType:'compute'],
		[code:'ECS', name:'Amazon EC2 Container Service', priceType:'compute'],
		[code:'EFS', name:'Amazon Elastic File System', priceType:'storage'],
		[code:'EI', name:'Amazon Elastic Inference', priceType:'compute'],
		[code:'EKS', name:'Amazon Elastic Container Service for Kubernetes'],
		[code:'ES', name:'Amazon Elasticsearch Service', priceType:'compute'],
		[code:'ETS', name:'ETS', priceType:'compute'],
		[code:'ElastiCache', name:'Amazon ElastiCache', priceType:'compute'],
		[code:'FSx', name:'Amazon FSx', priceType:'compute'],
		[code:'GameLift', name:'Amazon GameLift', priceType:'compute'],
		[code:'Glacier', name:'Amazon Glacier', priceType:'storage'],
		[code:'GuardDuty', name:'Amazon GuardDuty', priceType:'compute'],
		[code:'Inspector', name:'Amazon Inspector', priceType:'compute'],
		[code:'KinesisAnalytics', name:'Amazon Kinesis Analytics', priceType:'compute'],
		[code:'KinesisFirehose', name:'Amazon Kinesis Firehose', priceType:'compute'],
		[code:'Kinesis', name:'Amazon Kinesis', priceType:'compute'],
		[code:'Lex', name:'Amazon Lex', priceType:'compute'],
		[code:'Lightsail', name:'Amazon Lightsail', priceType:'compute'],
		[code:'ML', name:'Amazon ML', priceType:'compute'],
		[code:'MQ', name:'Amazon MQ', priceType:'compute'],
		[code:'MSK', name:'A fully managed, highly available, and secure service for Apache Kafka', priceType:'compute'],
		[code:'Macie', name:'Amazon Macie', priceType:'compute'],
		[code:'ManagedBlockchain', name:'Amazon Managed Blockchain', priceType:'compute'],
		[code:'Neptune', name:'Amazon Neptune', priceType:'compute'],
		[code:'Pinpoint', name:'Amazon Pinpoint', priceType:'compute'],
		[code:'Polly', name:'Amazon Polly', priceType:'compute'],
		[code:'QuickSight', name:'Amazon QuickSight', priceType:'compute'],
		[code:'RDS', name:'Amazon Relational Database Service', priceType:'compute'],
		[code:'Redshift', name:'Amazon Redshift', priceType:'compute'],
		[code:'Rekognition', name:'Amazon Rekognition', priceType:'compute'],
		[code:'Route53', name:'Amazon Route 53', priceType:'network'],
		[code:'S3GlacierDeepArchive', name:'Amazon S3 Glacier Deep Archive', priceType:'storage']
	]

	static costCategories = [
		//general
		[key:'Other', category:'other', group:'other', priceType:'other'],
		[key:'FreeEventsRecorded', category:'general', group:'other', priceType:'other'],
		[key:'ConfigurationItemRecorded', category:'general', group:'other', priceType:'other'],
		[key:'Dollar', category:'general', group:'support', priceType:'other'],
		[key:'DashboardsUsageHour-Basic', category:'general', group:'other', priceType:'other'],
		[key:'Invalidations', category:'general', group:'other', priceType:'other'],
		//mail
		[key:'DeliveryAttempts-SMTP', category:'mail', group:'other', priceType:'other'],
		//dns
		[key:'DNS-Queries', category:'dns', group:'other', priceType:'other'],
		[key:'HostedZone', category:'dns', group:'other', priceType:'other'],
		//processing
		[key:'DataProcessing-Bytes', category:'data-processing', group:'ec2', priceType:'compute'],
		//requests
		[key:'ApiGatewayRequest', category:'request', group:'ec2', priceType:'compute'],
		[key:'Request', category:'request', group:'ec2', priceType:'compute'],
		[key:'Requests-RBP', category:'request', group:'ec2', priceType:'compute'],
		[key:'Requests-Tier1', category:'request', group:'ec2', priceType:'compute'],
		[key:'Requests-Tier2', category:'request', group:'ec2', priceType:'compute'],
		[key:'Requests-Tier2-HTTPS', category:'request', group:'ec2', priceType:'compute'],
		//data-transfer
		[key:'S3-AWS-In-Bytes', category:'data-transfer', group:'s3', priceType:'storage'],
		[key:'S3-AWS-Out-Bytes', category:'data-transfer', group:'s3', priceType:'storage'],
		[key:'S3-DataTransfer-In-Bytes', category:'data-transfer', group:'s3', priceType:'storage'],
		[key:'S3-DataTransfer-Out-Bytes', category:'data-transfer', group:'s3', priceType:'storage'],
		[key:'DataTransfer-In-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		[key:'DataTransfer-Out-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		[key:'DataTransfer-Regional-Bytes', category:'data-transfer', group:'ec2', priceType:'compute'],
		[key:'AWS-Out-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		[key:'AWS-In-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		[key:'CloudFront-In-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		[key:'CloudFront-Out-Bytes', category:'data-transfer', group:'ec2', priceType:'network'],
		//monitor
		[key:'CW:AlarmMonitorUsage', category:'monitoring', group:'ec2', priceType:'compute'],
		[key:'CW:Requests', category:'monitoring', group:'ec2', priceType:'compute'],
		//storage
		[key:'-TimedStorage-ByteHrs', category:'storage', group:'ec2', priceType:'compute'],
		[key:'TimedStorage-ByteHrs', category:'storage', group:'s3', priceType:'storage'],
		[key:'S3-EBS:SnapshotUsage', category:'storage', group:'s3', priceType:'storage'],
		[key:'EBS:SnapshotUsage', category:'storage', group:'efs', priceType:'storage'],
		[key:'EBS:VolumeIOUsage', category:'storage', group:'ec2', priceType:'compute'],
		[key:'EBS:VolumeP-IOPS.piops', category:'storage', group:'ec2', priceType:'compute'],
		[key:'EBS:VolumeUsage', category:'storage', group:'ec2', priceType:'compute'],
		[key:'EBS:VolumeUsage.gp2', category:'storage', group:'ec2', priceType:'compute'],
		[key:'EBS:VolumeUsage.piops', category:'storage', group:'ec2', priceType:'compute'],
		[key:'EBSOptimized:', category:'storage', group:'ec2', priceType:'compute'],
		[key:':GP2-Storage', category:'storage', group:'efs', priceType:'storage'],
		[key:'Catalog-Storage', category:'storage', group:'efs', priceType:'storage'],
		//compute
		[key:'BoxUsage:', category:'compute', group:'ec2', priceType:'compute'],
		[key:'ESInstance:', category:'compute', group:'ec2', priceType:'compute'],
		[key:'HeavyUsage:', category:'compute', group:'ec2', priceType:'compute'],
		[key:'InstanceUsage:', category:'compute', group:'ec2', priceType:'compute'],
		[key:'NodeUsage:', category:'compute', group:'ec2', priceType:'compute'],
		//lambda
		[key:'Lambda-GB-Second', category:'compute', group:'ec2', priceType:'compute'],
		[key:'Lambda-', category:'compute', group:'ec2', priceType:'compute'],
		//elastic
		[key:'ES:', category:'elastic', group:'ec2', priceType:'compute'],
		//cache
		[key:'ElastiCache:BackupUsage', category:'cache', group:'ec2', priceType:'compute'],
		//network
		[key:'ElasticIP:AdditionalAddress', category:'eip', group:'ec2', priceType:'compute'],
		[key:'ElasticIP:IdleAddress', category:'eip', group:'ec2', priceType:'compute'],
		//load balancer
		[key:'LCUUsage', category:'load-balancer', group:'ec2', priceType:'compute'],
		[key:'LoadBalancerUsage', category:'load-balancer', group:'ec2', priceType:'compute'],
		//dynamo
		[key:'ReadCapacityUnit-Hrs', category:'dynamo', group:'ec2', priceType:'compute'],
		[key:'WriteCapacityUnit-Hrs', category:'dynamo', group:'ec2', priceType:'compute'],
		//direct connect
		[key:'DataXfer-In', category:'direct-connect', group:'other', priceType:'network'],
		[key:'DataXfer-Out', category:'direct-connect', group:'other', priceType:'network'],
		[key:'PortUsage:', category:'direct-connect', group:'other', priceType:'network'],
		//rds
		[key:'RDS:', category:'rds', group:'ec2', priceType:'compute'],
		//vpn
		[key:'VPN-Usage-Hours:', category:'vpn', group:'other', priceType:'network'],
		//keys
		[key:'KMS-Keys', category:'keys', group:'other', priceType:'other'],
		[key:'KMS-Requests', category:'keys', group:'other', priceType:'other']
	]

	class InvoiceProcessResult {
		public HashSet<Long> invoicesToReconcile = new HashSet<Long>()
		public HashSet<String> usageClouds = new HashSet<String>()
	}
}
