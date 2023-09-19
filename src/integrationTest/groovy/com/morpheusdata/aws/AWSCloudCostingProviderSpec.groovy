package com.morpheusdata.aws

import com.morpheusdata.core.MorpheusAsyncServices
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.MorpheusOperationDataService
import com.morpheusdata.core.MorpheusServices
import com.morpheusdata.core.MorpheusSynchronousOperationDataService
import com.morpheusdata.core.cloud.MorpheusCloudService
import com.morpheusdata.core.cloud.MorpheusComputeZoneRegionService
import com.morpheusdata.core.costing.MorpheusAccountInvoiceService
import com.morpheusdata.core.costing.MorpheusCostingService
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeZoneRegion
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
	def morpheusComputeZoneRegionService
	MorpheusCloudService morpheusCloudService
	AWSPlugin plugin
	MorpheusContext morpheusContext
	Cloud cloud

	void setup() {
		plugin = new AWSPlugin()
		morpheusContext = Mock(MorpheusContext)
		asyncServices = Mock(MorpheusAsyncServices)
		morpheusServices = Mock(MorpheusServices)
		morpheusCloudService = Mock(MorpheusCloudService)
		morpheusAccountInvoiceService = Mock(MorpheusAccountInvoiceService)
		morpheusCostingService = Mock(MorpheusCostingService)
		operationDataService = Mock(MorpheusOperationDataService)
		morpheusComputeZoneRegionService = Mock(MorpheusComputeZoneRegionService)
		morpheusSynchronousOperationDataService = Mock(MorpheusSynchronousOperationDataService)
		morpheusContext.getAsync() >> asyncServices
		morpheusContext.getServices() >> morpheusServices
		asyncServices.getCosting() >> morpheusCostingService
		asyncServices.getCloud() >> morpheusCloudService
		morpheusCloudService.getRegion() >> morpheusComputeZoneRegionService
		morpheusCostingService.getInvoice() >> morpheusAccountInvoiceService
		morpheusServices.getOperationData() >> morpheusSynchronousOperationDataService
		asyncServices.getOperationData() >> operationDataService
		awsCloudCostingProvider = new AWSCloudCostingProvider(plugin,morpheusContext)

		morpheusComputeZoneRegionService.list(_ as DataQuery) >> {args -> Observable.fromIterable([new ComputeZoneRegion(externalId:"us-west-1")]) as Observable<ComputeZoneRegion>}
		cloud = new Cloud()
		cloud.setConfigProperty("accessKey",System.getProperty('aws.accessKey'))
		cloud.setConfigProperty("secretKey",System.getProperty('aws.secretKey'))
		cloud.name = 'Test AWS Cloud'


	}

	void "it should be able to load aws reservation usage"() {
		given:
		when:
			def results = awsCloudCostingProvider.loadAwsReservationUsage(cloud,new Date())
			println results
		then:
			results.success == true

	}

	void "it should be able to load aws reservation guidance"() {
		given:
		when:
		def results = awsCloudCostingProvider.loadAwsReservationGuidance(cloud,new Date())
		println results
		then:
		results.success == true

	}

	void "it should be able to load aws savings plan guidance"() {
		given:
		when:
		def results = awsCloudCostingProvider.loadAwsSavingsPlanGuidance(cloud,new Date())
		println results
		then:
		results.success == true

	}

//
}
