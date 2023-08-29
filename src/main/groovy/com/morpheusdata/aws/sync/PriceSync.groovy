package com.morpheusdata.aws.sync

import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.CsvRow
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountPrice
import com.morpheusdata.model.AccountPriceSet
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.ServicePlanPriceSet
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.projection.ServicePlanIdentityProjection
import org.apache.commons.beanutils.PropertyUtils
import org.apache.http.Header
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.ParseException
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpRequestBase
import org.apache.http.config.MessageConstraints
import org.apache.http.config.Registry
import org.apache.http.config.RegistryBuilder
import org.apache.http.conn.ConnectTimeoutException
import org.apache.http.conn.HttpConnectionFactory
import org.apache.http.conn.ManagedHttpClientConnection
import org.apache.http.conn.routing.HttpRoute
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.socket.PlainConnectionSocketFactory
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.conn.ssl.X509HostnameVerifier
import org.apache.http.impl.DefaultHttpResponseFactory
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.BasicHttpClientConnectionManager
import org.apache.http.impl.conn.DefaultHttpResponseParser
import org.apache.http.impl.conn.DefaultHttpResponseParserFactory
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory
import org.apache.http.io.HttpMessageParser
import org.apache.http.io.HttpMessageParserFactory
import org.apache.http.io.HttpMessageWriterFactory
import org.apache.http.io.SessionInputBuffer
import org.apache.http.message.BasicHeader
import org.apache.http.message.BasicLineParser
import org.apache.http.message.LineParser
import org.apache.http.protocol.HttpContext
import org.apache.http.ssl.SSLContexts
import org.apache.http.util.CharArrayBuffer

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import java.lang.reflect.InvocationTargetException
import java.security.cert.X509Certificate
import groovy.util.logging.Slf4j

@Slf4j
class PriceSync {

	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, StorageVolumeType> storageVolumeTypes
	private Map<String, String> regionEndpoints
	private Map<String, ServicePlanIdentityProjection> servicePlans

	PriceSync(AWSPlugin plugin) {
		this.plugin = plugin
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {

			/*
			def rtn = [success:true]
			try {
				// This involves accessing the LARGE price csv file from Amazon, parsing it, and creating the needed price sets and prices
				// ONLY sync for the region(s) of interest
				def regions = opts.regions
				//def endpoints = [opts.zone.getConfigProperty('endpoint')]
				// Fetch all the AWS service plans and grab the instance types we care about
				def amazonInstanceTypes = ServicePlan.createCriteria().list() {
					createAlias('provisionType', 'provisionType')
					eq('active', true)
					eq('provisionType.code', 'amazon')
					like('code', 'amazon-%')
					projections {
						property('externalId')
					}
				}
				def regionReferenceData
				if(regions) {
					regionReferenceData = ReferenceData.createCriteria().list {
						eq('category', 'amazon.ec2.region')
						inList('keyValue', regions)
					}
				}


			//def regionTypes = regionReferenceData?.collect { it.name }?.flatten()
			*/

			def lineNumber = 0
			def columnMapping = [:]
			def totalRows = 0
			/*
			def COLUMNS_NEEDED = ['Instance Type', 'Location', 'Region Code', 'Unit', 'PricePerUnit', 'TermType', 'vCPU', 'Memory', 'Storage', 'CapacityStatus',
								  'Operating System', 'Pre Installed S/W', 'Tenancy', 'License Model', 'Product Family', 'usageType', 'SKU', 'Volume API Name']
								  */
			def regionIdx, instanceTypeIdx, termTypeIdx, osIdx, preInstallIdx, tenancyIdx, capStatusIdx, licenseIdx, productFamilyIdx, unitIdx, usageTypeIdx, volApiIdx, locationIdx
			def priceData = [:] // Map of instance, containing region, containing prices
			def loadBalancerPriceData = []
			def snapshotPriceData = []
			def storagePriceData = []
			HttpRequestBase request = new HttpGet("https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/index.csv")
			withClient([timeout: 240000]) { HttpClient client ->
				HttpResponse response
				CsvReader reader
				try {
					//response = client.execute(request)
					InputStream inputStream = new File("/Users/ddevilbiss/Downloads/aws_pricing.csv").newInputStream()
					// response.getEntity().getContent()
					CsvReader csvReader = CsvReader.builder().build(new BufferedReader(new InputStreamReader(inputStream)))
					for(CsvRow row : csvReader) {
						if (lineNumber == 4) {
							// Find which columns we care about
							row.fields.eachWithIndex { col, idx ->
								columnMapping[col] = idx
							}
							regionIdx = columnMapping['Region Code']
							instanceTypeIdx = columnMapping['Instance Type']
							termTypeIdx = columnMapping['TermType']
							osIdx = columnMapping['Operating System']
							preInstallIdx = columnMapping['Pre Installed S/W']
							tenancyIdx = columnMapping['Tenancy']
							capStatusIdx = columnMapping['CapacityStatus']
							licenseIdx = columnMapping['License Model']
							productFamilyIdx = columnMapping['Product Family']
							unitIdx = columnMapping['Unit']
							usageTypeIdx = columnMapping['usageType']
							volApiIdx = columnMapping['Volume API Name']
							locationIdx = columnMapping['Location']
						} else if (lineNumber > 4) {
							// Only interested in certain prices
							def regionCode = row.getField(regionIdx)
							def regionEndpoint = activeRegionEndpoints[regionCode]
							if (regionEndpoint) {
								def cost = new BigDecimal(row.getField(columnMapping['PricePerUnit']).toString() ?: '0.0')
								if (allServicePlans.containsKey(row.getField(instanceTypeIdx)) &&
									row.getField(termTypeIdx) == 'OnDemand' &&
									(row.getField(osIdx) == 'Linux' || row.getField(osIdx) == 'Windows') &&
									row.getField(preInstallIdx) == 'NA' &&
									row.getField(tenancyIdx) == 'Shared' && row.getField(capStatusIdx) == 'Used' &&
									(row.getField(licenseIdx) == 'No License required' || row.getField(licenseIdx) == 'License Included')) {
									totalRows++

									// Find or create the instance level
									def instanceToRegionMap = priceData[row.getField(instanceTypeIdx)]
									if (!instanceToRegionMap) {
										priceData[row.getField(instanceTypeIdx)] = [:]
										instanceToRegionMap = priceData[row.getField(instanceTypeIdx)]
									}
									// Find or create the region level
									def regionPrices = instanceToRegionMap[regionCode]
									if (!regionPrices) {
										instanceToRegionMap[regionCode] = []
										regionPrices = instanceToRegionMap[regionCode]
									}
									def instanceType = row.getField(instanceTypeIdx)
									regionPrices << [
										location: row.getField(locationIdx),
										instanceType: instanceType,
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										priceSetCode: "amazon.${instanceType}.${regionEndpoint}".toString(),
										priceCode: "amazon.${instanceType}.${regionEndpoint}.${row.getField(osIdx)}".toString(),
										platform: row.getField(osIdx),
										cost: cost
									]
								} else if (row.getField(productFamilyIdx) == 'Load Balancer' && row.getField(unitIdx) == 'Hrs') {
									totalRows++
									// Load balancer - ELB pricing
									//loadBalancerPriceData << line
								} else if (row.getField(productFamilyIdx) == 'Load Balancer-Application' && row.getField(unitIdx) == 'Hrs') {
									totalRows++
									// Load balancer - ALB  pricing
									//loadBalancerPriceData << line
								} else if (row.getField(productFamilyIdx) == 'Storage Snapshot' && row.getField(usageTypeIdx)?.endsWith('EBS:SnapshotUsage')) {
									totalRows++
									// Snapshot pricing
									//snapshotPriceData << line
									/*
									def storageType = line[volApiIdx]
									snapshotPriceData << [
										location: line[locationIdx],
										priceSetName: "Amazon Storage - ${line[locationIdx]}",
										priceSetCode: "amazon-storage.${regionEndpoints[regionCode]}",
										priceCode: "amazon.storage.${storageType == 'gp2' ? 'ebs' : storageType}.${regionEndpoints[regionCode]}",
									    regionCode: regionCode,
										storageType: storageType,
										cost: cost
									]*/
								} else if (row.getField(productFamilyIdx) == 'Storage' && row.getField(volApiIdx)) {
									totalRows++
									// Storage pricing
									storagePriceData << [
										location: row.getField(locationIdx),
										priceSetCode: "amazon-storage.${regionEndpoint}",
										priceCode: "amazon.storage.${row.getField(volApiIdx) == 'gp2' ? 'ebs' : row.getField(volApiIdx)}.${regionEndpoint}",
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										volApi: row.getField(volApiIdx),
										cost: cost
									]
								}
							}
						}
						if(lineNumber % 50000 == 0) println lineNumber
						lineNumber++
					}
				} catch(e) {
					e.printStackTrace()
				} finally {
					reader.close()
				}
			}

			//storagePriceData = new groovy.json.JsonSlurper().parseText('[{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.st1.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"st1","cost":0.0540000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.gp3.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"gp3","cost":0.0960000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.io1.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"io1","cost":0.1380000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.ebs.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"gp2","cost":0.1200000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.io2.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"io2","cost":0.1380000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.standard.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"standard","cost":0.0800000000},{"location":"US West (N. California)","priceSetName":"Amazon Storage - US West (N. California)","priceSetCode":"amazon-storage.ec2.us-west-1.amazonaws.com","priceCode":"amazon.storage.sc1.ec2.us-west-1.amazonaws.com","regionCode":"us-west-1","storageType":"sc1","cost":0.0180000000}]')
			//loadBalancerPriceData = new groovy.json.JsonSlurper().parseText('[["3BC8KFQBX2JJ2DUM","JRTCKXETXF","3BC8KFQBX2JJ2DUM.JRTCKXETXF.6YS6EN2CT7","OnDemand","$0.028 per LoadBalancer-hour (or partial hour)","2023-08-01","0","Inf","Hrs","0.0280000000","USD","","","","","Load Balancer","AmazonEC2","US West (N. California)","AWS Region","","","","","","","","","","","","","","","","","","","","","","ELB:Balancer","Standard Elastic Load Balancer","","","","","","USW1-LoadBalancerUsage","LoadBalancing","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","us-west-1","","Amazon Elastic Compute Cloud","","","",""],["NQDPABXBET36TG78","JRTCKXETXF","NQDPABXBET36TG78.JRTCKXETXF.6YS6EN2CT7","OnDemand","$0.0252 per Application LoadBalancer-hour (or partial hour)","2023-08-01","0","Inf","Hrs","0.0252000000","USD","","","","","Load Balancer-Application","AmazonEC2","US West (N. California)","AWS Region","","","","","","","","","","","","","","","","","","","","","","ELB:Balancer","LoadBalancer hourly usage by Application Load Balancer","","","","","","USW1-LoadBalancerUsage","LoadBalancing:Application","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","us-west-1","","Amazon Elastic Compute Cloud","","","",""]]')
			//snapshotPriceData = new groovy.json.JsonSlurper().parseText('[["3F2BXQPS4TRZ6SR6","JRTCKXETXF","3F2BXQPS4TRZ6SR6.JRTCKXETXF.6YS6EN2CT7","OnDemand","$0.055 per GB-Month of snapshot data stored - US West (Northern California)","2023-08-01","0","Inf","GB-Mo","0.0550000000","USD","","","","","Storage Snapshot","AmazonEC2","US West (N. California)","AWS Region","","","","","","","","","","","Amazon S3","","","","","","","","","","","","","","","","","","USW1-EBS:SnapshotUsage","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","","us-west-1","","Amazon Elastic Compute Cloud","","","",""]]')

			if(storagePriceData) {
				//updateStoragePricing(storagePriceData)
			}

			// log.info "about to iterate price data : ${priceData.size()}"
			while(priceData?.size() > 0) {
				def chunkedMap = priceData.take(50)
				priceData = priceData.drop(50)
				updateInstanceTypePricing(chunkedMap)
			}
			loadBalancerPriceData.each { regionPrice ->
				//updateLoadBalancerPricing(regionPrice, regionReferenceData, columnMapping)
			}
			snapshotPriceData.each { regionPrice ->
				//*updateSnapshotPricing(regionPrice, regionReferenceData, columnMapping)
			}
		} catch (e) {
			e.printStackTrace()
			//log.error("cacheServicePlansAndPricing error: ${e}", e)
			//rtn.success = false
		}
	}

	def updateInstanceTypePricing(Map instancePriceData) {
		Map<String, AccountPrice> savePrices = [:]
		Map<String, AccountPriceSet> priceSetMap = getPriceSets(
			instancePriceData.collect { instanceType, regionPrices ->
				regionPrices.collect { regionCode, prices -> "amazon.${instanceType}.${prices[0].regionEndpoint}" }
			}.flatten()
		)

		// map of existing service plan / price sets
		Map<Long, List> planPriceSets = [:]
		for(def planPriceSet in morpheusContext.async.servicePlanPriceSet.listIdentityProjections(priceSetMap.values().toList()).toList().blockingGet()) {
			if(!planPriceSet[planPriceSet.servicePlan.id]) {
				planPriceSet[planPriceSet.servicePlan.id] = []
			}
			planPriceSet[planPriceSet.servicePlan.id] << planPriceSet.priceSet
		}

		for(def instanceRegionPricesEntry in instancePriceData.entrySet()) {
			def amazonInstanceType = instanceRegionPricesEntry.key
			def regionPrices = instanceRegionPricesEntry.value
			ServicePlanIdentityProjection servicePlan = allServicePlans[amazonInstanceType]

			if (servicePlan) {
				log.debug "Got regions prices: ${regionPrices.size()}"

				for (def regionPricesEntry in regionPrices.entrySet()) {
					def regionCode = regionPricesEntry.key
					def prices = regionPricesEntry.value

					log.debug "... region: ${regionCode} has ${prices.size()} prices"

					for (def priceData in prices) {
						def priceSetType = com.morpheus.AccountPriceSet.PRICE_SET_TYPE.compute_plus_storage.toString()
						def priceUnit = 'hour'

						priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

						AccountPriceSet priceSet = priceSetMap[priceData.priceSetKey]
						if (!priceSet) {
							priceSet = new AccountPriceSet(
								name: "Amazon - ${servicePlan.externalId} - ${priceData.location}",
								code: priceData.priceSetCode,
								regionCode: priceData.regionCode,
								type: priceSetType,
								priceUnit: priceUnit,
								systemCreated: true
							)
							priceSet = morpheusContext.async.accountPriceSet.create(priceSet).blockingGet()
							priceSetMap[priceData.priceSetKey] = priceSet
						}

						// Add plan / price set association
						if(!planPriceSets[servicePlan.id]?.find { it.priceSet.id == priceSet.id}) {
							if(!planPriceSets[servicePlan.id]) {
								planPriceSets[servicePlan.id] = []
							}
							planPriceSets[servicePlan.id] << new ServicePlanPriceSet(servicePlan: new ServicePlan(id:servicePlan.id), priceSet: priceSet)
						}
					}

					// Create map of existing prices
					Map<String, AccountPrice> priceMap = morpheusContext.async.accountPrice.listByCode(prices.collect { it.priceCode }.unique()).toMap { it.code }.blockingGet()

					// Find the 'base' price which is the Linux platform price
					def baseCost = prices.find { it.platform == 'Linux' }?.cost ?: BigDecimal.ZERO

					for (def priceData in prices) {
						String name = "Amazon - ${servicePlan.externalId} - ${priceData.location} - ${priceData.platform}".toString()
						AccountPrice price = priceMap[priceData.priceCode]

						if (!price) {
							price = new AccountPrice(
								name: name,
								code: priceData.priceCode,
								priceType: priceData.platform == 'Linux' ? AccountPrice.PRICE_TYPE.compute : AccountPrice.PRICE_TYPE.platform,
								platform: priceData.platform,
								systemCreated: true,
								cost: priceData.platform == 'Linux' ? baseCost : ((priceData.cost ?: BigDecimal.ZERO) - baseCost).setScale(4, BigDecimal.ROUND_HALF_UP),
								incurCharges: 'always',
								priceUnit: 'month'
							)
							savePrices[priceData.priceCode] = price
						} else if (price.name != name) {
							price.name = name
							savePrices[priceData.priceCode] = price
						}
					}
				}
			}
		}

		if(savePrices) {
			//Create new
			morpheusContext.async.accountPrice.create(savePrices.values().findAll{ !it.id }.toList()).blockingGet()
			//Update existing
			morpheusContext.async.accountPrice.save(savePrices.values().findAll{ it.id }.toList()).blockingGet()
		}

		List allPriceData = instancePriceData.collect { instanceType, regionPrices ->
			regionPrices.collect { regionCode, prices -> prices }
		}.flatten()

		// Load newly created prices instanceTypes:regions:prices
		Map<String, AccountPrice> priceMap = morpheusContext.async.accountPrice.listByCode(allPriceData.collect { it.priceCode }).toMap { it.code }.blockingGet()

		// Associate price to price set for newly created
		for(def priceData in allPriceData) {
			AccountPriceSet priceSet = priceSetMap[priceData.priceSetKey]
			AccountPrice price = priceMap[priceData.priceCode]

			if(priceSet && price) {
				morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
			}
		}

		// Always add the AWS EBS storage price to every price set
		/*
		for(AccountPriceSet priceSet in priceSetMap.values()) {
			List<AccountPrice> storagePrices = morpheusContext.async.accountPrice.list(
				new DataQuery().withFilter('code', '~=', "amazon.storage.%.${priceSet.regionCode}")
			).toList().blockingGet()

			for(AccountPrice storagePrice in storagePrices) {
				morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, storagePrice)
			}
		}

		// Save new plan / price set associations
		for(Long planId in planPriceSets.keySet()) {
			List<ServicePlanPriceSet> adds = []

			for(def planPriceSet in planPriceSets[planId]) {
				if(planPriceSet instanceof ServicePlanPriceSet) {
					adds << planPriceSet
				}
			}
			if(adds) {
				morpheusContext.async.servicePlanPriceSet.create(adds)
			}
		}

		/*
								// Always add the AWS EBS storage price to every price set
						def storagePrices = com.morpheus.AccountPrice.findAllByCodeLike("amazon.storage.%.${regionCode}")
						storagePrices?.each { storagePrice ->
							priceManagerService.addToPriceSet(priceSet, storagePrice)
						}
						priceManagerService.addPriceSetToPlan(servicePlan, priceSet)


						prices.each { p ->
							try {
								def priceInfo = [platform: p[columnMapping['Operating System']],
												 cost    : p[columnMapping['PricePerUnit']]?.toString(),
												 baseCost: baseCost
								]
								def (price, errors) = priceManagerService.getOrCreatePrice(
									[name         : "Amazon - ${servicePlan.externalId} - ${region} - ${priceInfo.platform}",
									 code         : "amazon.${servicePlan.externalId}.${regionCode}.${priceInfo.platform}",
									 priceType    : priceInfo.platform == 'Linux' ? com.morpheus.AccountPrice.PRICE_TYPE.compute : com.morpheus.AccountPrice.PRICE_TYPE.platform,
									 platform     : priceInfo.platform,
									 systemCreated: true,
									 cost         : priceInfo.platform == 'Linux' ? new BigDecimal(priceInfo.baseCost?.toString() ?: '0.0') : (new BigDecimal(priceInfo.cost?.toString() ?: '0.0') - new BigDecimal(priceInfo.baseCost?.toString() ?: '0.0')).setScale(4, BigDecimal.ROUND_HALF_UP),  // Everything but Linux is a delta,
									 priceUnit    : 'hour']
								)
								priceManagerService.addToPriceSet(priceSet, price)
							} catch (e) {
								log.error "Error in working on price ${p}, ${e}", e
							}
						}
						// Always add the AWS EBS storage price to every price set
						def storagePrices = com.morpheus.AccountPrice.findAllByCodeLike("amazon.storage.%.${regionCode}")
						storagePrices?.each { storagePrice ->
							priceManagerService.addToPriceSet(priceSet, storagePrice)
						}
						priceManagerService.addPriceSetToPlan(servicePlan, priceSet)
					} catch (e) {
						log.error("Error in working on prices for priceset in region ${region}, ${e}", e)
					}
				}
				servicePlan.save(failOnError: true)
			}
		}
*/
	}

	def updateStoragePricing(storagePriceData) {
		// Existing price sets
		Map<String, AccountPriceSet> priceSetMap = getPriceSets(storagePriceData.collect{ it.priceSetCode })

		// Create map of existing prices
		Map<String, AccountPrice> priceMap = morpheusContext.async.accountPrice.listByCode(storagePriceData.collect {it.priceCode}.unique()).toMap { it.code }.blockingGet()
		Map<String, AccountPrice> savePrices = [:]

		for(def priceData in storagePriceData) {
			def storageVolumeType = allStorageVolumeTypes["amazon-${priceData.volApi}".toString()]
			if (storageVolumeType) {
				def priceUnit = 'month'
				def priceSetType = AccountPriceSet.PRICE_SET_TYPE.component.toString()

				priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

				if (!priceSetMap.containsKey(priceData.priceSetKey)) {
					AccountPriceSet priceSet = new AccountPriceSet(
						name: "Amazon Storage - ${priceData.location}",
						code: priceData.priceSetCode,
						regionCode: priceData.regionCode,
						type: priceSetType,
						priceUnit: priceUnit,
						systemCreated: true
					)
					priceSet = morpheusContext.async.accountPriceSet.create(priceSet).blockingGet()
					priceSetMap[priceData.priceSetKey] = priceSet
					morpheusContext.async.accountPriceSet.addPriceSetToParent(priceSet, storageVolumeType)
				}

				AccountPrice price = priceMap[priceData.priceCode]
				String name = "Amazon - EBS (${priceData.volApi}) - ${priceData.location}".toString()

				if (!price) {
					price = new AccountPrice(
						name: "Amazon - EBS (${priceData.volApi}) - ${priceData.location}",
						code: priceData.priceCode,
						priceType: AccountPrice.PRICE_TYPE.storage,
						volumeType: storageVolumeType,
						systemCreated: true,
						cost: priceData.cost.setScale(4, BigDecimal.ROUND_HALF_UP),
						incurCharges: 'always',
						priceUnit: 'month'
					)
					savePrices[priceData.priceCode] = price
				} else if(price.name != name) {
					price.name = name
					savePrices[priceData.priceCode] = price
				}
			}
		}

		// Create or update prices
		if(savePrices) {
			morpheusContext.async.accountPrice.save(savePrices.values().toList()).blockingGet()
		}

		// Load all matching prices
		priceMap = morpheusContext.async.accountPrice.listByCode(storagePriceData.collect{ it.priceCode }.unique()).toMap { it.code }.blockingGet()

		// Associate price to price set for newly created
		for(def priceData in storagePriceData) {
			AccountPriceSet priceSet = priceSetMap[priceData.priceSetKey]
			AccountPrice price = priceMap[priceData.priceCode]

			if(priceSet && price) {
				//morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
			}
		}
	}

	private Map<String, String> getActiveRegionEndpoints() {
		if(regionEndpoints == null) {
			Map<String, Long> codeRegionIdMap = [:]
/*
			// get full set of cloud regions
			morpheusContext.async.cloud.region.list(new DataQuery().)
			morpheusContext.async.cloud.listByType('amazon').toList().blockingGet().each { Cloud cloud ->
				morpheusContext.async.cloud.region.listIdentityProjections(cloud.id).toList().blockingGet().each { cloudRegion ->
					codeRegionIdMap[cloudRegion.externalId] = cloudRegion.id
				}
			}

			// return map of shortcode to full region
			regionEndpoints = [:]
			morpheusContext.async.cloud.region.listById(codeRegionIdMap.values()).blockingSubscribe {
				regionEndpoints[it.externalId] = it.internalId
			}*/
		}
		regionEndpoints
	}

	private Map<String, StorageVolumeType> getAllStorageVolumeTypes() {
		storageVolumeTypes ?: (storageVolumeTypes = morpheusContext.async.storageVolume.storageVolumeType.listAll().toMap { it.code }.blockingGet())
	}
	private def withClient(opts, Closure cl) {
		HttpClientBuilder clientBuilder = HttpClients.custom()
		clientBuilder.setHostnameVerifier(new X509HostnameVerifier() {
			boolean verify(String host, SSLSession sess) {
				return true
			}

			void verify(String host, SSLSocket ssl) {}

			void verify(String host, String[] cns, String[] subjectAlts) {}

			void verify(String host, X509Certificate cert) {}

		})
		SSLContext sslcontext = SSLContexts.createSystemDefault()

		//ignoreSSL(sslcontext)
		SSLConnectionSocketFactory sslConnectionFactory = new SSLConnectionSocketFactory(sslcontext) {
			@Override
			Socket connectSocket(int connectTimeout, Socket socket, HttpHost host, InetSocketAddress remoteAddress, InetSocketAddress localAddress, HttpContext context) throws IOException, ConnectTimeoutException {
				if(socket instanceof SSLSocket) {
					try {
						socket.setEnabledProtocols(['SSLv3', 'TLSv1', 'TLSv1.1', 'TLSv1.2'] as String[])
						PropertyUtils.setProperty(socket, "host", host.getHostName())
					} catch(NoSuchMethodException ex) {
					}
					catch(IllegalAccessException ex) {
					}
					catch(InvocationTargetException ex) {
					}
				}
				return super.connectSocket(opts.timeout ?: 30000, socket, host, remoteAddress, localAddress, context)
			}
		}

		HttpMessageParserFactory<HttpResponse> responseParserFactory = new DefaultHttpResponseParserFactory() {

			@Override
			HttpMessageParser<HttpResponse> create(SessionInputBuffer ibuffer, MessageConstraints constraints) {
				LineParser lineParser = new BasicLineParser() {

					@Override
					Header parseHeader(final CharArrayBuffer buffer) {
						try {
							return super.parseHeader(buffer)
						} catch (ParseException ex) {
							return new BasicHeader(buffer.toString(), null)
						}
					}

				}

				return new DefaultHttpResponseParser(ibuffer, lineParser, DefaultHttpResponseFactory.INSTANCE, constraints ?: MessageConstraints.DEFAULT) {

					@Override
					protected boolean reject(final CharArrayBuffer line, int count) {
						//We need to break out of forever head reads
						if(count > 100) {
							return true
						}
						return false
					}

				}
			}
		}
		clientBuilder.setSSLSocketFactory(sslConnectionFactory)
		Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory> create()
			.register("https", sslConnectionFactory)
			.register("http", PlainConnectionSocketFactory.INSTANCE)
			.build()
		HttpMessageWriterFactory<HttpRequest> requestWriterFactory = new DefaultHttpRequestWriterFactory()
		HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory = new ManagedHttpClientConnectionFactory(
			requestWriterFactory, responseParserFactory)
		BasicHttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager(registry, connFactory)
		clientBuilder.setConnectionManager(connectionManager)
		// Configure Proxy Settings if the zone is configured
		/*
		def proxySettings = settingsService.getKarmanProxySettings()
		def proxyHost = proxySettings.proxyHost
		def proxyPort = proxySettings.proxyPort
		if(proxyHost && proxyPort) {
			def proxyUser = proxySettings.proxyUser
			def proxyPassword = proxySettings.proxyPassword
			def proxyWorkstation = proxySettings.proxyWorkstation ?: null
			def proxyDomain = proxySettings.proxyDomain ?: null
			clientBuilder.setProxy(new HttpHost(proxyHost, proxyPort))
			if(proxyUser) {
				CredentialsProvider credsProvider = new BasicCredentialsProvider()
				NTCredentials ntCreds = new NTCredentials(proxyUser, proxyPassword, proxyWorkstation, proxyDomain)
				credsProvider.setCredentials(new AuthScope(proxyHost, proxyPort), ntCreds)

				clientBuilder.setDefaultCredentialsProvider(credsProvider)
				clientBuilder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy())
			}
		}*/
		HttpClient client = clientBuilder.build()
		try {
			return cl.call(client)
		} finally {
			connectionManager.shutdown()
		}
	}

	private Map<String, ServicePlanIdentityProjection> getAllServicePlans() {
		if(servicePlans == null) {
			servicePlans = morpheusContext.async.servicePlan.listIdentityProjections(
				new DataQuery().withFilters([
					new DataFilter('provisionType.code', 'amazon'),
					new DataFilter('code', '=~', 'amazon-')
				])
			).toMap{ it.externalId }.blockingGet()
		}
		servicePlans
	}

	private Map<String, AccountPriceSet> getPriceSets(List<String> priceSetCodes) {
		morpheusContext.async.accountPriceSet.listByCode(priceSetCodes.unique()).toMap{
			"${it.code}:${it.regionCode}:${it.type}:${it.priceUnit}".toString()
		}.blockingGet()
	}
}
