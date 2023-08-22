package com.morpheusdata.aws

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusOperationDataService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusSynchronousOperationDataService
import com.morpheusdata.core.costing.MorpheusAccountInvoiceService
import com.morpheusdata.core.costing.MorpheusCostingService
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
}
