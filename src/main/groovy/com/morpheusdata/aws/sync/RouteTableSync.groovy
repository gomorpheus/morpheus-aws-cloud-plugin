package com.morpheusdata.aws.sync

import com.amazonaws.services.ec2.model.Route
import com.amazonaws.services.ec2.model.RouteTable
import com.morpheusdata.aws.AWSPlugin
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.util.SyncTask
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.CloudPool
import com.morpheusdata.model.MorpheusModel
import com.morpheusdata.model.NetworkRoute
import com.morpheusdata.model.NetworkRouteTable
import com.morpheusdata.model.NetworkRouter
import com.morpheusdata.model.projection.CloudPoolIdentity
import com.morpheusdata.model.projection.InstanceScaleIdentityProjection
import com.morpheusdata.model.projection.NetworkRouteIdentityProjection
import com.morpheusdata.model.projection.NetworkRouteTableIdentityProjection
import com.morpheusdata.model.projection.NetworkRouterIdentityProjection
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

@Slf4j
class RouteTableSync {
	private Cloud cloud
	private MorpheusContext morpheusContext
	private AWSPlugin plugin
	private Map<String, InstanceScaleIdentityProjection> scaleTypes
	private List<NetworkRouterIdentityProjection> routers

	RouteTableSync(AWSPlugin plugin, Cloud cloud) {
		this.plugin = plugin
		this.cloud = cloud
		this.morpheusContext = plugin.morpheusContext
	}

	def execute() {
		try {
			morpheusContext.async.cloud.pool.listIdentityProjections(cloud.id, null, null).buffer(50).concatMap {List<CloudPoolIdentity> zonePools ->
				Map<Long,CloudPoolIdentity> pools = zonePools.collectEntries{[(it.id):it]}
				morpheusContext.async.network.router.list(new DataQuery().withFilters(
						new DataFilter<String>("refType","ComputeZonePool"),
						new DataFilter<List<Long>>("refId","in",zonePools.collect{it.id}
						)
				)).map { NetworkRouter router ->
					return [router: router, zonePool: pools[router.refId]]
				}
			}.blockingSubscribe { LinkedHashMap<String, MorpheusModel> poolRouter ->
				NetworkRouter router = poolRouter.router as NetworkRouter
				CloudPoolIdentity zonePool = poolRouter.zonePool as CloudPoolIdentity
				def amazonClient = plugin.getAmazonClient(cloud, false, zonePool.regionCode)
				def cloudItems = AmazonComputeUtility.listRouteTables(amazonClient: amazonClient, filterVpcId: zonePool.externalId).routeTableList
				Observable<NetworkRouteTableIdentityProjection> existingRecords = morpheusContext.async.network.routeTable.listIdentityProjections(zonePool.id)
				SyncTask<NetworkRouteTableIdentityProjection, RouteTable, NetworkRouteTable> syncTask = new SyncTask<>(existingRecords, cloudItems)
				syncTask.addMatchFunction { NetworkRouteTableIdentityProjection existingItem, RouteTable cloudItem ->
					existingItem.externalId == cloudItem.routeTableId
				}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkRouteTableIdentityProjection, NetworkRouteTable>> updateItems ->
					morpheusContext.async.network.routeTable.listById(updateItems.collect { it.existingItem.id } as Collection<Long>)
				}.onAdd { itemsToAdd ->
					addMissingNetworkRouteTables(itemsToAdd, zonePool, router)
				}.onUpdate { List<SyncTask.UpdateItem<NetworkRouteTable, RouteTable>> updateItems ->
					updateMatchedNetworkRouteTables(updateItems, zonePool, router)
				}.onDelete { removeItems ->
					removeMissingNetworkRouteTables(removeItems)
				}.start()
			}
		} catch(Exception ex) {
			log.error("RouteTableSync error: {}", ex, ex)
		}
	}

	private addMissingNetworkRouteTables(Collection<RouteTable> addList, CloudPoolIdentity zonePool, NetworkRouter router) {
		log.debug "addMissingNetworkRouteTables: ${cloud} ${zonePool.regionCode} ${addList.size()}"
		def adds = []

		for(RouteTable cloudItem in addList) {
			adds << new NetworkRouteTable(
				name: cloudItem.tags?.find{it.key == 'Name'}?.value ?: cloudItem.routeTableId,
				code: "aws.route.table.${cloud.id}.${cloudItem.routeTableId}",
				category: "aws.route.table.${cloud.id}",
				externalId: cloudItem.routeTableId,
				zonePool: new CloudPool(id: zonePool.id)
			)
		}

		// Create em all!
		log.debug "About to create ${adds.size()} instance scales"
		morpheusContext.async.network.routeTable.create(adds).blockingGet()

		// Sync newly added routes
		syncRouteTableRoutes(addList, zonePool, router)
	}

	private updateMatchedNetworkRouteTables(List<SyncTask.UpdateItem<NetworkRouteTable, RouteTable>> updateList, CloudPoolIdentity zonePool, NetworkRouter router) {
		log.debug "updateMatchedNetworkRouteTables: ${cloud} ${zonePool.regionCode} ${updateList.size()}"
		List<SyncTask.UpdateItem<NetworkRouteTable, RouteTable>> saveList = []

		for(def updateItem in updateList) {
			def existingItem = updateItem.existingItem
			def cloudItem = updateItem.masterItem
			def name = cloudItem.tags?.find{it.key == 'Name'}?.value ?: cloudItem.routeTableId

			if(existingItem.name != name) {
				existingItem.name = name
				saveList << updateItem
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} instance scales"
			morpheusContext.async.instance.scale.save(saveList.collect { it.existingItem })
		}
		syncRouteTableRoutes(updateList.collect { it.masterItem }, zonePool, router)
	}

	private removeMissingNetworkRouteTables(Collection<NetworkRouteTableIdentityProjection> removeList) {
		log.debug "removeMissingNetworkRouteTables: ${cloud} ${removeList.size()}"
		morpheusContext.async.network.routeTable.remove(removeList).blockingGet()
	}

	private syncRouteTableRoutes(List<RouteTable> amazonRouteTables, CloudPoolIdentity zonePool, NetworkRouter router) {
		Map<String, RouteTable> routeTableMap = amazonRouteTables.collectEntries { [it.routeTableId, it] }
		Collection<Long> routeTableIds = []
		morpheusContext.async.network.routeTable.listIdentityProjections(zonePool.id).blockingSubscribe { NetworkRouteTableIdentityProjection routeTable ->
			if (routeTableMap.containsKey(routeTable.externalId)) {
				routeTableIds << routeTable.id
			}
		}

		List<NetworkRouteTable> routeTables = morpheusContext.async.network.routeTable.listById(routeTableIds).toList().blockingGet()

		for(NetworkRouteTableIdentityProjection routeTable in routeTables) {
			List<Route> cloudItems = routeTableMap[routeTable.externalId]?.routes
			Observable<NetworkRouteIdentityProjection> existingRecords = morpheusContext.async.network.router.route.listIdentityProjections(routeTable)
			SyncTask<NetworkRouteIdentityProjection, Route, NetworkRoute> syncTask = new SyncTask<>(existingRecords, cloudItems)
			syncTask.addMatchFunction { NetworkRouteIdentityProjection existingItem, Route cloudItem ->
				existingItem.externalId == buildRouteExternalId(cloudItem)
			}.withLoadObjectDetailsFromFinder { List<SyncTask.UpdateItemDto<NetworkRouteIdentityProjection, NetworkRoute>> updateItems ->
				morpheusContext.async.network.router.route.listById(updateItems.collect { it.existingItem.id } as Collection<Long>)
			}.onAdd { itemsToAdd ->
				addMissingNetworkRoutes(itemsToAdd, routeTable, router)
			}.onUpdate { List<SyncTask.UpdateItem<NetworkRoute, Route>> updateItems ->
				updateMatchedNetworkRoutes(updateItems, routeTable, router)
			}.onDelete { removeItems ->
				removeMissingNetworkRoutes(removeItems, router)
			}.start()
		}
	}

	private addMissingNetworkRoutes(List<Route> cloudItems, NetworkRouteTableIdentityProjection routeTable, NetworkRouter router) {
		def adds = []
		for(Route cloudItem in cloudItems) {
			NetworkRoute route = new NetworkRoute(externalId: buildRouteExternalId(cloudItem))
			configureRoute(routeTable, route, cloudItem)
			adds << route
		}
		if(adds) {
			morpheusContext.async.network.router.route.create(router, adds).blockingGet()
		}

	}

	private updateMatchedNetworkRoutes(List<SyncTask.UpdateItem<NetworkRoute, Route>> updateList, NetworkRouteTable routeTable, NetworkRouter router) {
		List<SyncTask.UpdateItem<NetworkRoute, Route>> saveList = []
		Boolean save

		for(def updateItem in updateList) {
			if(configureRoute(routeTable, updateItem.existingItem, updateItem.masterItem)) {
				save = true
			}

			if(router && !router.routes.find { it.id == updateItem.existingItem.id }) {
				log.debug "Adding route ${updateItem.existingItem} to router ${router}"
				save = true
			}
			if(save) {
				saveList << updateItem
			}
		}

		if(saveList) {
			log.debug "About to update ${saveList.size()} network routes"
			morpheusContext.async.network.router.route.save(router, saveList.collect { it.existingItem }).blockingGet()
		}
	}

	private removeMissingNetworkRoutes(List<NetworkRouteIdentityProjection> removeList, NetworkRouter router) {
		log.debug "About to remove ${removeList.size()} network routes"
		morpheusContext.async.network.router.route.remove(router?.id, removeList).blockingGet()
	}

	def buildRouteExternalId(amazonRoute) {
		def externalId = amazonRoute.getDestinationCidrBlock()
		if(amazonRoute.getEgressOnlyInternetGatewayId()){
			externalId += amazonRoute.getEgressOnlyInternetGatewayId()
		} else if(amazonRoute.getGatewayId()){
			externalId += amazonRoute.getGatewayId()
		} else if(amazonRoute.getInstanceId()) {
			externalId += amazonRoute.getInstanceId()
		} else if(amazonRoute.getLocalGatewayId()){
			externalId += amazonRoute.getLocalGatewayId()
		} else if(amazonRoute.getNatGatewayId()){
			externalId += amazonRoute.getNatGatewayId()
		} else if(amazonRoute.getNetworkInterfaceId()){
			externalId += amazonRoute.getNetworkInterfaceId()
		} else if(amazonRoute.getTransitGatewayId()){
			externalId += amazonRoute.getTransitGatewayId()
		} else if(amazonRoute.getVpcPeeringConnectionId()){
			externalId += amazonRoute.getVpcPeeringConnectionId()
		}
		externalId
	}

	Boolean configureRoute(NetworkRouteTable routeTable, NetworkRoute route, Route amazonRoute) {
		log.debug "configureRoute: ${route}, ${amazonRoute}"
		def save = false
		try {
			def destinationType
			def destination
			if(amazonRoute.getEgressOnlyInternetGatewayId()){
				destination = amazonRoute.getEgressOnlyInternetGatewayId()
				destinationType = 'EGRESS_ONLY_INTERNET_GATEWAY'
			} else if(amazonRoute.getGatewayId()){
				destination = amazonRoute.getGatewayId()
				if(destination?.startsWith('igw')) {
					destinationType = 'INTERNET_GATEWAY'
				} else {
					destinationType = 'GATEWAY'
				}
			} else if(amazonRoute.getInstanceId()) {
				destination = amazonRoute.getInstanceId()
				destinationType = 'INSTANCE'
			} else if(amazonRoute.getLocalGatewayId()){
				destination = amazonRoute.getLocalGatewayId()
				destinationType = 'LOCAL_GATEWAY'
			} else if(amazonRoute.getNatGatewayId()){
				destination = amazonRoute.getNatGatewayId()
				destinationType = 'NAT_GATEWAY'
			} else if(amazonRoute.getNetworkInterfaceId()){
				destination = amazonRoute.getNetworkInterfaceId()
				destinationType = 'NETWORK_INTERFACE'
			} else if(amazonRoute.getTransitGatewayId()){
				destination = amazonRoute.getTransitGatewayId()
				destinationType = 'TRANSIT_GATEWAY'
			} else if(amazonRoute.getVpcPeeringConnectionId()){
				destination = amazonRoute.getVpcPeeringConnectionId()
				destinationType = 'VPC_PEERING_CONNECTION'
			}

			if(route.destination != destination){
				route.destination = destination
				save = true
			}

			if(route.destinationType != destinationType){
				route.destinationType = destinationType
				save = true
			}

			if(route.name != amazonRoute.getDestinationCidrBlock()){
				route.name = amazonRoute.getDestinationCidrBlock()
				save = true
			}
			if(route.code != "aws.route.${cloud.id}.${route.externalId}"){
				route.code = "aws.route.${cloud.id}.${route.externalId}"
				save = true
			}
			if(route.category != "aws.route.${cloud.id}"){
				route.category = "aws.route.${cloud.id}"
				save = true
			}
			if(route.routeTable?.id != routeTable.id){
				route.routeTable = routeTable
				save = true
			}
			if(amazonRoute.getDestinationCidrBlock() && route.source != amazonRoute.getDestinationCidrBlock()){
				route.source = amazonRoute.getDestinationCidrBlock()
				save = true
			}
			if(amazonRoute.getDestinationIpv6CidrBlock() && route.source != amazonRoute.getDestinationIpv6CidrBlock()){
				route.source = amazonRoute.getDestinationIpv6CidrBlock()
				save = true
			}
			if(route.status != amazonRoute.getState()) {
				route.status = amazonRoute.getState()
				save = true
			}
			def manageable = amazonRoute.getOrigin() == 'CreateRoute'
			if(route.deletable != manageable) {
				route.deletable = manageable
				save = true
			}
			if(route.editable != manageable) {
				route.editable = manageable
				save = true
			}
		} catch(e) {
			log.error "Error in configureRoute: ${e}", e
		}
		save
	}
}
