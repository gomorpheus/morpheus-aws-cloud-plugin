package com.morpheusdata.aws.sync

import com.morpheusdata.aws.ALBLoadBalancerProvider
import com.morpheusdata.aws.ELBLoadBalancerProvider
import com.morpheusdata.model.NetworkLoadBalancerType
import com.morpheusdata.model.PricePlanPriceSet
import com.morpheusdata.model.projection.CloudTypeIdentityProjection
import de.siegmar.fastcsv.reader.CsvReader
import de.siegmar.fastcsv.reader.CsvRow
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.AccountPrice
import com.morpheusdata.model.AccountPriceSet
import com.morpheusdata.model.PricePlan
import com.morpheusdata.model.PricePlanType
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
			def lineNumber = 0
			def columnMapping = [:]
			def totalRows = 0
			def regionIdx, instanceTypeIdx, termTypeIdx, osIdx, preInstallIdx, tenancyIdx, capStatusIdx, licenseIdx, productFamilyIdx, unitIdx, usageTypeIdx, volApiIdx, locationIdx
			def instancePriceData = [:] // Map of instance, containing region, containing prices
			def loadBalancerPriceData = []
			def snapshotPriceData = []
			def storagePriceData = []

			HttpRequestBase request = new HttpGet("https://pricing.us-east-1.amazonaws.com/offers/v1.0/aws/AmazonEC2/current/index.csv")
			withClient([timeout: 240000]) { HttpClient client ->
				HttpResponse response
				CsvReader reader
				try {
					InputStream inputStream = client.execute(request).getEntity().getContent()
					reader = CsvReader.builder().build(new BufferedReader(new InputStreamReader(inputStream)))
					for(CsvRow row : reader) {
						if (lineNumber == 5) {
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
						} else if (lineNumber > 5) {
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
									def instanceToRegionMap = instancePriceData[row.getField(instanceTypeIdx)]
									if (!instanceToRegionMap) {
										instancePriceData[row.getField(instanceTypeIdx)] = [:]
										instanceToRegionMap = instancePriceData[row.getField(instanceTypeIdx)]
									}
									// Find or create the region level
									def regionPrices = instanceToRegionMap[regionCode]
									if (!regionPrices) {
										instanceToRegionMap[regionCode] = []
										regionPrices = instanceToRegionMap[regionCode]
									}
									def instanceType = row.getField(instanceTypeIdx)
									regionPrices << [
										instanceType: instanceType,
										regionName: row.getField(locationIdx),
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										priceSetCode: "amazon.${instanceType}.${regionEndpoint}".toString(),
										priceCode: "amazon.${instanceType}.${regionEndpoint}.${row.getField(osIdx)}".toString(),
										platform: row.getField(osIdx),
										cost: cost
									]
								} else if (row.getField(productFamilyIdx) in ['Load Balancer', 'Load Balancer-Application'] && row.getField(unitIdx) == 'Hrs') {
									totalRows++
									if(!row.getField(productFamilyIdx) in ['Load Balancer', 'Load Balancer-Application']) {
										log.info "Unknown load balancer of type ${row.getField(productFamilyIdx)}"
									}
									Boolean isELB = row.getField(productFamilyIdx) == 'Load Balancer'
									loadBalancerPriceData << [
										regionName: row.getField(locationIdx),
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										priceSetCode: "amazon-${isELB ? 'elb' : 'alb'}.${regionEndpoint}".toString(),
										priceCode: "amazon-${isELB ? 'elb' : 'alb'}.${regionEndpoint}".toString(),
										cost: cost,
										isELB: isELB
									]
								} else if (row.getField(productFamilyIdx) == 'Storage Snapshot' && row.getField(usageTypeIdx)?.endsWith('EBS:SnapshotUsage')) {
									totalRows++
									// Snapshot pricing
									snapshotPriceData << [
										regionName: row.getField(locationIdx),
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										priceSetCode: "amazon-snapshot.${regionEndpoint}".toString(),
										priceCode: "amazon-snapshot.${regionEndpoint}".toString(),
										cost: cost
									]
								} else if (row.getField(productFamilyIdx) == 'Storage' && row.getField(volApiIdx)) {
									totalRows++
									// Storage pricing
									storagePriceData << [
										regionName: row.getField(locationIdx),
										regionEndpoint: regionEndpoint,
										regionCode: regionCode,
										priceSetCode: "amazon-storage.${regionCode}".toString(),
										priceCode: "amazon.storage.${row.getField(volApiIdx) == 'gp2' ? 'ebs' : row.getField(volApiIdx)}.${regionEndpoint}".toString(),
										volApi: row.getField(volApiIdx),
										cost: cost
									]
								}
							}
						}
						lineNumber++
					}
				} catch(e) {
					e.printStackTrace()
				} finally {
					reader.close()
				}
			}

			if(storagePriceData) {
				updateStoragePricing(storagePriceData)
			}

			log.debug "about to iterate price data : ${instancePriceData.size()}"
			while(instancePriceData) {
				updateInstanceTypePricing(instancePriceData.take(50))
				instancePriceData = instancePriceData.drop(50)
			}

			if(loadBalancerPriceData) {
				updateLoadBalancerPricing(loadBalancerPriceData)
			}

			if(snapshotPriceData) {
				updateSnapshotPricing(snapshotPriceData)
			}
		} catch (e) {
			log.error("cacheServicePlansAndPricing error: ${e}", e)
		}
	}

	def updateInstanceTypePricing(Map instancePriceData) {
		Map<String, AccountPrice> priceSaves = [:]
		Map<String, AccountPriceSet> existingPriceSets = getPriceSets(
			instancePriceData.collect { instanceType, regionPrices ->
				regionPrices.collect { regionCode, prices -> prices[0].priceSetCode }
			}.flatten()
		)

		// map of existing service plan / price sets
		Map<Long, List> planPriceSets = [:]
		for(def planPriceSet in morpheusContext.async.servicePlanPriceSet.listIdentityProjections(existingPriceSets.values().toList()).toList().blockingGet()) {
			if(!planPriceSets[planPriceSet.servicePlan.id]) {
				planPriceSets[planPriceSet.servicePlan.id] = []
			}
			planPriceSets[planPriceSet.servicePlan.id] << planPriceSet
		}

		for(def instanceRegionPrices in instancePriceData) {
			def amazonInstanceType = instanceRegionPrices.key
			def regionPrices = instanceRegionPrices.value
			ServicePlanIdentityProjection servicePlan = allServicePlans[amazonInstanceType]

			if (servicePlan) {
				log.debug "Got regions prices: ${regionPrices.size()}"

				for (def regionPricesEntry in regionPrices.entrySet()) {
					def regionCode = regionPricesEntry.key
					def prices = regionPricesEntry.value
					def storagePrices = morpheusContext.async.accountPrice.list(
						new DataQuery().withFilter('code', '=~', "amazon.storage.%.${prices[0].regionEndpoint}")
					).toList().blockingGet()

					log.debug "... region: ${regionCode} has ${prices.size()} prices"

					for (def priceData in prices) {
						def priceSetType = AccountPriceSet.PRICE_SET_TYPE.compute_plus_storage.toString()
						def priceUnit = 'hour'

						priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

						AccountPriceSet priceSet = existingPriceSets[priceData.priceSetKey]
						if (!priceSet) {
							priceSet = morpheusContext.async.accountPriceSet.create(
								new AccountPriceSet(
									name: "Amazon - ${servicePlan.externalId} - ${priceData.regionName}",
									code: priceData.priceSetCode,
									regionCode: priceData.regionCode,
									type: priceSetType,
									priceUnit: priceUnit,
									systemCreated: true
								)
							).blockingGet()
							existingPriceSets[priceData.priceSetKey] = priceSet
						}

						// Add plan / price set association
						if(!planPriceSets[servicePlan.id]?.find { it.priceSet.id == priceSet.id }) {
							if(!planPriceSets[servicePlan.id]) {
								planPriceSets[servicePlan.id] = []
							}
							planPriceSets[servicePlan.id] << new ServicePlanPriceSet(servicePlan: new ServicePlan(id:servicePlan.id), priceSet: priceSet)
						}

						// Always add the AWS EBS storage price to every price set
						Map priceSetPrices = morpheusContext.async.accountPrice.listIdentityProjections(priceSet).toMap { it.id }.blockingGet()

						for(AccountPrice storagePrice in storagePrices) {
							if(!priceSetPrices.containsKey(storagePrice.id)) {
								morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, storagePrice).blockingGet()
							}
						}
					}

					// Create map of existing prices
					Map<String, AccountPrice> existingPrices = morpheusContext.async.accountPrice.listByCode(prices.collect { it.priceCode }.unique()).toMap { it.code }.blockingGet()

					// Find the 'base' price which is the Linux platform price
					def baseCost = prices.find { it.platform == 'Linux' }?.cost ?: BigDecimal.ZERO

					for (def priceData in prices) {
						String name = "Amazon - ${servicePlan.externalId} - ${priceData.regionName} - ${priceData.platform}".toString()
						AccountPrice price = existingPrices[priceData.priceCode]

						if (!price) {
							price = new AccountPrice(
								name: name,
								code: priceData.priceCode,
								priceType: priceData.platform == 'Linux' ? AccountPrice.PRICE_TYPE.compute : AccountPrice.PRICE_TYPE.platform,
								platform: priceData.platform,
								systemCreated: true,
								cost: priceData.platform == 'Linux' ? baseCost : ((priceData.cost ?: BigDecimal.ZERO) - baseCost).setScale(4, BigDecimal.ROUND_HALF_UP),
								incurCharges: 'always',
								priceUnit: 'hour'
							)
							priceSaves[priceData.priceCode] = price
						} else if (price.name != name) {
							price.name = name
							priceSaves[priceData.priceCode] = price
						}
					}
				}
			}
		}

		savePrices(priceSaves.values().toList())
		addPricesToPriceSets(
			instancePriceData.collect { instanceType, regionPrices -> regionPrices.collect { regionCode, prices -> prices }}.flatten(),
			existingPriceSets
		)

		// Save new plan / price set associations
		List<ServicePlanPriceSet> adds = planPriceSets.values().flatten().findAll { !it.id }
		if(adds) {
			morpheusContext.async.servicePlanPriceSet.create(adds).blockingGet()
		}
	}

	def updateStoragePricing(storagePriceData) {
		Map<String, AccountPrice> priceSaves = [:]

		// Existing price sets
		Map<String, AccountPriceSet> existingPriceSets = getPriceSets(storagePriceData.collect{ it.priceSetCode })

		// Create map of existing prices
		Map<String, AccountPrice> existingPrices = morpheusContext.async.accountPrice.listByCode(storagePriceData.collect {it.priceCode}.unique()).toMap { it.code }.blockingGet()

		for(def priceData in storagePriceData) {
			def storageVolumeType = allStorageVolumeTypes["amazon-${priceData.volApi}".toString()]
			if (storageVolumeType) {
				def priceUnit = 'month'
				def priceSetType = AccountPriceSet.PRICE_SET_TYPE.component.toString()

				priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

				if (!existingPriceSets.containsKey(priceData.priceSetKey)) {
					AccountPriceSet priceSet = new AccountPriceSet(
						name: "Amazon Storage - ${priceData.regionName}",
						code: priceData.priceSetCode,
						regionCode: priceData.regionCode,
						type: priceSetType,
						priceUnit: priceUnit,
						systemCreated: true
					)
					priceSet = morpheusContext.async.accountPriceSet.create(priceSet).blockingGet()
					existingPriceSets[priceData.priceSetKey] = priceSet
					morpheusContext.async.accountPriceSet.addPriceSetToParent(priceSet, storageVolumeType)
				}

				AccountPrice price = existingPrices[priceData.priceCode]
				String priceName = "Amazon - EBS (${priceData.volApi}) - ${priceData.regionName}".toString()

				if (!price) {
					price = new AccountPrice(
						name: priceName,
						code: priceData.priceCode,
						priceType: AccountPrice.PRICE_TYPE.storage,
						volumeType: storageVolumeType,
						systemCreated: true,
						cost: priceData.cost.setScale(4, BigDecimal.ROUND_HALF_UP),
						incurCharges: 'always',
						priceUnit: 'month'
					)
					priceSaves[priceData.priceCode] = price
				} else if(price.name != priceName) {
					price.name = priceName
					priceSaves[priceData.priceCode] = price
				}
			}
		}
		savePrices(priceSaves.values().toList())
		addPricesToPriceSets(storagePriceData, existingPriceSets)
	}

	def updateLoadBalancerPricing(loadBalancerPriceData) {
		Map<String, AccountPrice> priceSaves = [:]

		Map<String, PricePlan> pricePlans = morpheusContext.async.pricePlan.list(
			new DataQuery().withFilter('code', 'in', ['amazon-elb', 'amazon-alb'])
		).toMap{ it.code }.blockingGet()

		Map<String, AccountPriceSet> existingPriceSets = getPriceSets(loadBalancerPriceData.collect { it.priceSetCode })
		Map<String, AccountPrice> existingPrices = morpheusContext.async.accountPrice.listByCode(loadBalancerPriceData.collect { it.priceCode }.unique()).toMap { it.code }.blockingGet()
		Map<String, NetworkLoadBalancerType> lbTypes = morpheusContext.async.loadBalancer.type.list(
			new DataQuery().withFilter('code', 'in', [ELBLoadBalancerProvider.PROVIDER_CODE, ALBLoadBalancerProvider.PROVIDER_CODE])
		).toMap{ it.code }.blockingGet()

		// map of existing price plan / price sets
		Map<Long, List> planPriceSets = [:]
		for(def planPriceSet in morpheusContext.async.pricePlanPriceSet.listIdentityProjections(new DataQuery().withFilter('priceSet.id', 'in', existingPriceSets.values().collect { it.id })).toList().blockingGet()) {
			if(!planPriceSets[planPriceSet.pricePlan.id]) {
				planPriceSets[planPriceSet.pricePlan.id] = []
			}
			planPriceSets[planPriceSet.pricePlan.id] << planPriceSet
		}

		for(def priceData in loadBalancerPriceData) {
			def refId = lbTypes[priceData.isELB ? ELBLoadBalancerProvider.PROVIDER_CODE : ALBLoadBalancerProvider.PROVIDER_CODE].id
			String planCode = "amazon-${priceData.isELB ? 'elb' : 'alb'}".toString()
			PricePlan pricePlan = pricePlans[planCode]
			if(!pricePlan) {
				String planName = "Amazon ${priceData.isELB ? 'ELB' : 'ALB'}"

				pricePlan = morpheusContext.async.pricePlan.create(
					new PricePlan(
						refType: 'NetworkLoadBalancerType',
						refTypeName: planName,
						refId: refId,
						code: planCode,
						name: planName,
						editable: false,
						deletable: false,
						type: new PricePlanType(code: 'loadBalancer')
					)
				).blockingGet()
				pricePlans[planCode] = pricePlan
			}

			def priceUnit = 'hour'
			def priceSetType = AccountPriceSet.PRICE_SET_TYPE.load_balancer.toString()

			priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

			AccountPriceSet priceSet = existingPriceSets[priceData.priceSetKey]

			if(!priceSet) {
				priceSet = morpheusContext.async.accountPriceSet.create(
					new AccountPriceSet(
						regionCode: priceData.regionCode,
						code: priceData.priceSetCode,
						name: "${pricePlan.name} - ${priceData.regionName}",
						priceUnit: priceUnit,
						type: priceSetType,
						systemCreated: true
					)
				).blockingGet()
				existingPriceSets[priceData.priceSetKey] = priceSet
			}

			// Add plan / price set association
			if(!planPriceSets[pricePlan.id]?.find { it.priceSet.id == priceSet.id }) {
				if(!planPriceSets[pricePlan.id]) {
					planPriceSets[pricePlan.id] = []
				}
				planPriceSets[pricePlan.id] << new PricePlanPriceSet(pricePlan: pricePlan, priceSet: priceSet)
			}

			AccountPrice price = existingPrices[priceData.priceCode]
			String priceName = "${pricePlan.name} - ${priceData.regionName}"
			if (!price) {
				price = new AccountPrice(
					name: "${pricePlan.name} - ${priceData.regionName}",
					code: priceData.priceCode,
					priceType: AccountPrice.PRICE_TYPE.load_balancer,
					systemCreated: true,
					cost: priceData.cost.setScale(4, BigDecimal.ROUND_HALF_UP),
					priceUnit: 'hour'
				)
				priceSaves[priceData.priceCode] = price
			} else if (price.name != priceName) {
				price.name = priceName
				priceSaves[priceData.priceCode] = price
			}
		}
		savePrices(priceSaves.values().toList())
		addPricesToPriceSets(loadBalancerPriceData, existingPriceSets)

		// Save new plan / price set associations
		List<PricePlanPriceSet> adds = planPriceSets.values().flatten().findAll { !it.id }
		if(adds) {
			morpheusContext.async.pricePlanPriceSet.bulkCreate(adds).blockingGet()
		}
	}

	def updateSnapshotPricing(snapshotPriceData) {
		Map<String, AccountPrice> priceSaves = [:]
		Map<String, AccountPriceSet> existingPriceSets = getPriceSets(snapshotPriceData.collect { it.priceSetCode })
		Map<String, AccountPrice> existingPrices = morpheusContext.async.accountPrice.listByCode(snapshotPriceData.collect { it.priceCode }.unique()).toMap { it.code }.blockingGet()

		// map of existing price plan / price sets
		Map<Long, List> planPriceSets = [:]
		for(def planPriceSet in morpheusContext.async.pricePlanPriceSet.listIdentityProjections(new DataQuery().withFilter('priceSet.id', 'in', existingPriceSets.values().collect { it.id })).toList().blockingGet()) {
			if(!planPriceSets[planPriceSet.pricePlan.id]) {
				planPriceSets[planPriceSet.pricePlan.id] = []
			}
			planPriceSets[planPriceSet.pricePlan.id] << planPriceSet
		}

		PricePlan pricePlan = morpheusContext.async.pricePlan.find(new DataQuery().withFilter('code', 'amazon-snapshot')).blockingGet()

		if(!pricePlan) {
			pricePlan = morpheusContext.async.pricePlan.create(
				new PricePlan(
					refType: 'ComputeZoneType',
					refTypeName: 'Amazon Snapshot',
					refId: morpheusContext.async.cloud.type.find(new DataQuery().withFilter('code', 'amazon')).blockingGet().id,
					code: 'amazon-snapshot',
					name: 'Amazon Snapshot',
					editable: false,
					deletable: false,
					type: new PricePlanType(code: 'snapshot')
				)
			).blockingGet()
		}

		for(def priceData in snapshotPriceData) {
			def priceUnit = 'month'
			def priceSetType = AccountPriceSet.PRICE_SET_TYPE.snapshot.toString()

			priceData.priceSetKey = "${priceData.priceSetCode}:${priceData.regionCode}:${priceSetType}:${priceUnit}".toString()

			AccountPriceSet priceSet = existingPriceSets[priceData.priceSetKey]
			if(!priceSet) {
				priceSet = morpheusContext.async.accountPriceSet.create(
					new AccountPriceSet(
						regionCode: priceData.regionCode,
						code: priceData.priceSetCode,
						name: "${pricePlan.name} - ${priceData.regionName}",
						priceUnit: priceUnit,
						type: priceSetType,
						systemCreated: true
					)
				).blockingGet()
				existingPriceSets[priceData.priceSetKey] = priceSet
			}

			// Add plan / price set association
			if(!planPriceSets[pricePlan.id]?.find { it.priceSet.id == priceSet.id }) {
				if(!planPriceSets[pricePlan.id]) {
					planPriceSets[pricePlan.id] = []
				}
				planPriceSets[pricePlan.id] << new PricePlanPriceSet(pricePlan: pricePlan, priceSet: priceSet)
			}

			AccountPrice price = existingPrices[priceData.priceCode]
			String priceName = "${pricePlan.name} - ${priceData.regionName}"
			if (!price) {
				price = new AccountPrice(
					name: priceName,
					code: priceData.priceCode,
					priceType: AccountPrice.PRICE_TYPE.storage,
					volumeType: allStorageVolumeTypes['s3Object'],
					systemCreated: true,
					cost: priceData.cost.setScale(4, BigDecimal.ROUND_HALF_UP),
					incurCharges: 'always',
					priceUnit: 'month'
				)
				priceSaves[priceData.priceCode] = price
			} else if (price.name != priceName) {
				price.name = priceName
				priceSaves[priceData.priceCode] = price
			}
		}
		savePrices(priceSaves.values().toList())
		addPricesToPriceSets(snapshotPriceData, existingPriceSets)

		// Save new plan / price set associations
		List<PricePlanPriceSet> adds = planPriceSets.values().flatten().findAll { !it.id }
		if(adds) {
			morpheusContext.async.pricePlanPriceSet.bulkCreate(adds).blockingGet()
		}
	}

	private savePrices(List<AccountPrice> prices) {
		//Create new
		List<AccountPrice> adds = prices.findAll{ !it.id }.toList()
		if(adds) {
			morpheusContext.async.accountPrice.bulkCreate(adds).blockingGet()
		}
		//Update existing
		List<AccountPrice> saves = prices.findAll{ it.id }.toList()
		if(saves) {
			morpheusContext.async.accountPrice.bulkSave(saves).blockingGet()
		}
	}

	private addPricesToPriceSets(allPriceData, priceSets) {
		// Load price set price mappings
		Map priceSetPrices = [:]
		for(def priceSet in priceSets.values()) {
			priceSetPrices[priceSet.id] = morpheusContext.async.accountPrice.listIdentityProjections(priceSet).toMap { it.id }.blockingGet()
		}

		// Load newly created prices instanceTypes:regions:prices
		Map<String, AccountPrice> prices = morpheusContext.async.accountPrice.listByCode(allPriceData.collect { it.priceCode }).toMap { it.code }.blockingGet()

		// Associate price to price set for newly created
		for(def priceData in allPriceData) {
			AccountPriceSet priceSet = priceSets[priceData.priceSetKey]
			AccountPrice price = prices[priceData.priceCode]

			if(priceSet && price && !priceSetPrices[priceSet.id]?.containsKey(price.id)) {
				morpheusContext.async.accountPriceSet.addToPriceSet(priceSet, price).blockingGet()
			}
		}
	}

	private Map<String, String> getActiveRegionEndpoints() {
		if(regionEndpoints == null) {
			//Map<String, Long> codeRegionIdMap = [:]
			regionEndpoints = [:]
			CloudTypeIdentityProjection cloudType = morpheusContext.async.cloud.type.listIdentityProjections(new DataQuery().withFilter('code', 'amazon')).blockingFirst()
			morpheusContext.async.cloud.region.list(new DataQuery().withFilter('zone.zoneType.id', cloudType.id)).blockingSubscribe{
				regionEndpoints[it.externalId] = it.internalId
			}
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
