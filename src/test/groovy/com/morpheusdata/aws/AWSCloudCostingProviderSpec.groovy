package com.morpheusdata.aws

import com.bertramlabs.plugins.karman.StorageProvider
import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusOperationDataService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusSynchronousOperationDataService
import com.morpheusdata.core.costing.MorpheusAccountInvoiceService
import com.morpheusdata.core.costing.MorpheusCostingService
import com.morpheusdata.model.Cloud
import groovy.json.JsonSlurper
import io.reactivex.Observable
import spock.lang.Specification
import spock.lang.Subject

class AWSCloudCostingProviderSpec extends Specification {
	@Subject
	AWSCloudCostingProvider awsCloudCostingProvider
	MorpheusSynchronousOperationDataService morpheusSynchronousOperationDataService
	MorpheusOperationDataService operationDataService
	MorpheusAsyncServices asyncServices
	MorpheusServices morpheusServices
	MorpheusCostingService morpheusCostingService
	MorpheusAccountInvoiceService morpheusAccountInvoiceService
	AWSPlugin plugin
	MorpheusContext morpheusContext

	void setup() {
		plugin = Mock(AWSPlugin)
		morpheusContext = Mock(MorpheusContext)
		asyncServices = Mock(MorpheusAsyncServices)
		morpheusServices = Mock(MorpheusServices)
		morpheusAccountInvoiceService = Mock(MorpheusAccountInvoiceService)
		morpheusCostingService = Mock(MorpheusCostingService)
		operationDataService = Mock(MorpheusOperationDataService)
		morpheusSynchronousOperationDataService = Mock(MorpheusSynchronousOperationDataService)
		morpheusContext.getAsync() >> asyncServices
		morpheusContext.getServices() >> morpheusServices
		asyncServices.getCosting() >> morpheusCostingService
		morpheusCostingService.getInvoice() >> morpheusAccountInvoiceService
		morpheusServices.getOperationData() >> morpheusSynchronousOperationDataService
		asyncServices.getOperationData() >> operationDataService
		awsCloudCostingProvider = new AWSCloudCostingProvider(plugin,morpheusContext)
	}


	void "getTemporaryCostFile should return a temp file"() {
		given:
		when:
			def costFile = awsCloudCostingProvider.getTemporaryCostFile()
		then:
			costFile != null
	}

	void "should generate line item hash from string"() {
		given:
		when:
			String hash = awsCloudCostingProvider.getLineItemHash("abc12345")
			String hash2 = awsCloudCostingProvider.getLineItemHash("abc12345")
			String hash3 = awsCloudCostingProvider.getLineItemHash("abc123456")

		then:
			hash != null
			hash == hash2
			hash != hash3
	}

	void "should produce a stream of billing rows from passed in manifest and csv report key"() {
		given:
			File manifestFile = new File("src/test/resources/cloudability-Manifest.json")
			StorageProvider provider = StorageProvider.create(type:'local', basePath: new File("src/test/resources").canonicalPath)
			def manifest = new JsonSlurper().parseText(manifestFile.text)
		when:
		Observable<Map> fileObserver = awsCloudCostingProvider.createCsvStreamer(provider,'.',"cloudability-00001.csv.gz","202308",manifest)
		def rows = fileObserver.toList().blockingGet()
		then:
			rows.size() > 0
	}

//	void "should generate a costing report definition map from a clouds set of config properties"() {
//		given:
//			Cloud cloud = new Cloud()
//			cloud.setConfigProperty("costingReport")
//	}
}
