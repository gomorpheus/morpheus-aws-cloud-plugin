package com.morpheusdata.aws

import com.amazonaws.services.ec2.AmazonEC2
import com.morpheusdata.aws.utils.AmazonComputeUtility
import com.morpheusdata.core.AbstractProvisionProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.providers.ComputeProvisionProvider
import com.morpheusdata.core.providers.WorkloadProvisionProvider
import com.morpheusdata.core.util.ComputeUtility
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeCapacityInfo
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.ComputeServerInterface
import com.morpheusdata.model.HostType
import com.morpheusdata.model.Icon
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.Process
import com.morpheusdata.model.ProcessEvent
import com.morpheusdata.model.ServicePlan
import com.morpheusdata.model.StorageVolume
import com.morpheusdata.model.StorageVolumeType
import com.morpheusdata.model.VirtualImage
import com.morpheusdata.model.Workload
import com.morpheusdata.model.provisioning.WorkloadRequest
import com.morpheusdata.request.ResizeRequest
import com.morpheusdata.response.PrepareWorkloadResponse
import com.morpheusdata.response.ProvisionResponse
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class RDSProvisionProvider extends AbstractProvisionProvider implements WorkloadProvisionProvider, ComputeProvisionProvider, WorkloadProvisionProvider.ResizeFacet {
    AWSPlugin plugin
    MorpheusContext morpheusContext

    public static final PROVISION_PROVIDER_CODE = 'amazon-rds-provision-provider'
    public static final PROVISION_TYPE_CODE = 'rds'

    public RDSProvisionProvider(AWSPlugin plugin, MorpheusContext ctx) {
        super()
        this.plugin = plugin
        this.morpheusContext = ctx
    }

	@Override
	Icon getCircularIcon() {
		return new Icon(path:"amazon-rds-circular.svg", darkPath: "amazon-rds-circular.svg")
	}

    /**
     * Provides a Collection of OptionType inputs that need to be made available to various provisioning Wizards
     * @return Collection of OptionTypes
     */
    @Override
    Collection<OptionType> getOptionTypes() {
        def options = []
        options << new OptionType(
            name:'RDS db engine version',
            code:'plugin.aws.rds.dbEngineVersion',
            fieldName:'dbEngineVersion',
            displayOrder:100,
            fieldLabel:'DB Engine Version',
            fieldCode:'gomorpheus.optiontype.DbEngineVersion',
            fieldGroup:'Options',
            required:false,
            editable:false,
            inputType:OptionType.InputType.SELECT,
            optionSource:'amazonDbEngineVersion',
            optionSourceType:'amazon',
            global:false,
            fieldContext:'config',
            category:'provisionType.rds'
        )
        options << new OptionType(
            name:'RDS public ip',
            code:'plugin.aws.rds.dbPublic',
            fieldName:'dbPublic',
            displayOrder:101,
            fieldLabel:'Public IP',
            fieldCode:'gomorpheus.optiontype.PublicIp',
            fieldGroup:'Options',
            required:false,
            editable:false,
            inputType:OptionType.InputType.CHECKBOX,
            global:false,
            fieldContext:'config',
            category:'provisionType.rds',
            defaultValue:'on'
        )
        options << new OptionType(
            name:'RDS db subnet group',
            code:'plugin.aws.rds.dbSubnetGroup',
            fieldName:'dbSubnetGroup',
            displayOrder:102,
            fieldLabel:'DB Subnet Group',
            fieldCode:'gomorpheus.optiontype.DbSubnetGroup',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.SELECT,
            optionSource:'amazonDbSubnetGroup',
            optionSourceType:'amazon',
            dependsOnCode: 'config.resourcePoolId',
            global:false,
            fieldContext:'config',
            category:'provisionType.rds'
        )
        options << new OptionType(
            name:'RDS backup retention days',
            code:'plugin.aws.rds.dbBackupRetention',
            fieldName:'dbBackupRetention',
            displayOrder:103,
            fieldLabel:'Backup Retention Days',
            fieldCode:'gomorpheus.optiontype.BackupRetentionDays',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.TEXT,
            global:false,
            fieldContext:'config',
            category:'provisionType.rds',
            defaultValue: 0
        )
        options << new OptionType(
            name:'RDS security group',
            code:'plugin.aws.rds.securityId',
            fieldName:'securityId',
            displayOrder:104,
            fieldLabel:'Security Group',
            fieldCode:'gomorpheus.optiontype.SecurityGroup',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.SELECT,
            optionSource:'amazonSecurityGroup',
            optionSourceType:'amazon',
            global:false,
            fieldContext:'config',
            category:'provisionType.rds'
        )
        return options
    }

    Collection<OptionType> getMysqlOptionTypes() {
        def options = []
        options << new OptionType(
            name:'MySQL Root Password',
            code:'plugin.aws.rds.mysql.rootPassword',
            fieldName:'rootPassword',
            displayOrder:5,
            fieldLabel:'Root Password',
            fieldCode:'gomorpheus.optiontype.RootPassword',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.PASSWORD,
            global:false,
            placeHolder:'root password',
            fieldContext:'config',
            category:'instanceType.mysql'
        )
        options << new OptionType(
            name:'MySQL Username',
            code:'plugin.aws.rds.mysql.username',
            fieldName:'serviceUsername',
            displayOrder:6,
            fieldLabel:'Username',
            fieldCode:'gomorpheus.optiontype.Username',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.TEXT,
            global:false,
            placeHolder:'Create a MySQL admin user',
            fieldContext:'domain',
            category:'instanceType.mysql'
        )
        options << new OptionType(
            name:'MySQL Password',
            code:'plugin.aws.rds.mysql.password',
            fieldName:'servicePassword',
            displayOrder:7,
            fieldLabel:'Password',
            fieldCode:'gomorpheus.optiontype.Password',
            fieldGroup:'Options',
            required:true,
            editable:false,
            inputType:OptionType.InputType.PASSWORD,
            global:false,
            placeHolder:'admin user password',
            fieldContext:'domain',
            category:'instanceType.mysql'
        )
        return options
    }

    ServiceResponse validateInstance(Instance instance, Map opts) {
        return ServiceResponse.success()
    }

    /**
     * Validates the provided provisioning options of a workload. A return of success = false will halt the
     * creation and display errors
     * @param opts options
     * @return Response from API. Errors should be returned in the errors Map with the key being the field name and the error
     * message as the value.
     */
    @Override
    ServiceResponse validateWorkload(Map opts) {
        log.debug("validateContainer: ${opts}")
        def rtn = ServiceResponse.success()
        try {
            log.debug("validateContainer: ${opts}")
            def validationOpts = [
                securityId:opts.securityId,
                dbSubnetGroup:opts.config.dbSubnetGroup ?: opts.dbSubnetGroup
            ]
            if(opts.securityGroups)
                validationOpts.securityGroups = opts.securityGroups
            def validationResults = AmazonComputeUtility.validateRdsServerConfig(validationOpts)
            if(!validationResults.success) {
                rtn.success = false
                rtn.errors += validationResults.errors
            }
        } catch(e) {
            log.error("validate container error: ${e}", e)
        }
        log.debug("validateContainer: ${rtn}")
        return rtn
    }

    @Override
    ServiceResponse<ProvisionResponse> runWorkload(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        log.debug "runWorkload: ${workload} ${workloadRequest} ${opts}"
        def amazonClient
        ProvisionResponse provisionResponse = new ProvisionResponse(success: true, installAgent: false)
        ComputeServer server = workload.server
        try {
            Cloud cloud = server.cloud
            VirtualImage virtualImage = server.sourceImage
            amazonClient = getAmazonRdsClient(cloud,false, server.resourcePool?.regionCode)

            def runConfig = buildWorkloadRunConfig(workload, workloadRequest, opts)
            runConfig.amazonClient = amazonClient
            opts.amazonClient = amazonClient
            opts.server = server

            log.debug("create server: ${runConfig}")
            def createResults = AmazonComputeUtility.createRdsServer(runConfig)
            log.info("create server: ${createResults}")
            if(createResults.success == true) {
                def workloadConfig = workload.configMap
                def instance = createResults.server
                if (instance) {
                    server.sshUsername = workloadConfig.user
                    server.sshPassword = workloadConfig.pass
                    server.externalId = instance.getDBInstanceIdentifier()
                    provisionResponse.externalId = server.externalId
                    morpheus.async.computeServer.bulkSave([server]).blockingGet()
                    def statusResults = AmazonComputeUtility.checkRdsServerReady(opts)
                    log.info("server status: ${statusResults}")
                    if (statusResults.success == true) {
                        //good to go
                        def serverDetails = AmazonComputeUtility.getRdsServerDetail(opts)
                        if (serverDetails.success == true) {
                            //fill in ip address.
                            def privateIp = serverDetails.server.getEndpoint()?.getAddress()
                            def publicIp = privateIp
                            if (privateIp) {
                                def newInterface = false
                                server.internalIp = privateIp
                                opts.network = server.interfaces?.find { it.ipAddress == privateIp }
                                if (opts.network == null) {
                                    opts.network = new ComputeServerInterface(name: 'eth0', ipAddress: privateIp, primaryInterface: true)
                                    newInterface = true
                                }
                                if (publicIp) {
                                    opts.network.publicIpAddress = publicIp
                                    server.externalIp = publicIp
                                }
                                if (newInterface == true)
                                    server.interfaces.add(opts.network)
                            }

                            server.sshHost = privateIp ?: publicIp
                            server.status = 'provisioned'
                            server.serverType = 'ami' //probably set this earlier via a field on container type
                            server.osDevice = '/dev/vda'
                            server.dataDevice = '/dev/vda'
                            server.lvmEnabled = false
                            server.managed = true

                            def maxStorage = server.volumes?.find{it.rootVolume == true}.maxStorage
                            server.capacityInfo = new ComputeCapacityInfo(
                                maxCores: 1,
                                maxMemory:workload.getConfigProperty('maxMemory').toLong(),
                                maxStorage:maxStorage
                            )

                            // Save off the compute server one more time with all the spiffy details
                            provisionResponse.success = morpheus.async.computeServer.bulkSave([server]).blockingGet()

                        } else {
                            provisionResponse.success = false
                            provisionResponse.message = serverDetails.msg ?: 'Failed to get server details'
                        }
                    } else {
                        provisionResponse.success = false
                        provisionResponse.message = statusResults.msg ?: 'Server not ready'
                    }
                } else {
                    provisionResponse.success = false
                    provisionResponse.message = 'Failed to acquire aws instance'
                }
            }
            return new ServiceResponse<ProvisionResponse>(success:provisionResponse.success, data:provisionResponse)

        } catch (Throwable t) {
            log.error "runWorkload: ${t}", t
            provisionResponse.setError(t.message)
            return new ServiceResponse(success:false, msg:t.message, error:t.message, data:provisionResponse)
        }
    }

    @Override
    ServiceResponse finalizeWorkload(Workload workload) {
        return ServiceResponse.success()
    }

    @Override
    ServiceResponse stopWorkload(Workload workload) {
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            def opts = [container:workload]
            opts.server = workload.server
            def cloud = workload.server.cloud
            opts.amazonClient = getAmazonRdsClient(cloud, false, workload.server.region.regionCode)
            def stopResp = AmazonComputeUtility.stopRdsServer(opts)
            rtn.success = stopResp.success
            if (!rtn.success) {
                rtn.msg = "Failed to stop rds instance ${workload.instance.name}"
            }
        }
        catch(Throwable t) {
            log.error("stopWorkload error: ${t}", t)
            rtn.msg = t.message
        }
        return rtn
    }

    @Override
    ServiceResponse startWorkload(Workload workload) {
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            def opts = [container:workload]
            def cloud = workload.server.cloud
            opts.server = workload.server
            opts.amazonClient = getAmazonRdsClient(cloud, false, workload.server.region.regionCode)
            def startResp = AmazonComputeUtility.startRdsServer(opts)
            rtn.success = startResp.success
            if (!startResp.success) {
                rtn.msg = "Failed to start RDS instance ${workload.instance.name}"
            }
        }
        catch(Throwable t) {
            log.error("startWorkload error: ${t}", t)
            rtn.msg = t.message
        }
        return rtn
    }

    @Override
    ServiceResponse restartWorkload(Workload workload) {
        stopWorkload(workload)
        return startWorkload(workload)
    }

    @Override
    ServiceResponse removeWorkload(Workload workload, Map opts) {
        def rtn = ServiceResponse.success()
        try {
            if(workload.server?.externalId) {
                def removeOpts = [container:workload]
                def containerConfig  = workload.configMap
                def dbEngine = morpheus.async.referenceData.get(containerConfig.dbEngineVersion.toLong()).blockingGet()
                removeOpts.dbEngine = dbEngine.xRef
                removeOpts.server = workload.server
                removeOpts.zone = workload.server.cloud
                removeOpts.account = removeOpts.server.account
                removeOpts.amazonClient = getAmazonRdsClient(removeOpts.zone, false, removeOpts.server.region.regionCode)
                def removeResults = AmazonComputeUtility.deleteRdsServer(removeOpts)
                if(removeResults.success == true) {
                    rtn.data = [removeServer:true]
                } else {
                    rtn.success = false
                    rtn.msg = 'Failed to remove vm'
                }
            } else {
                rtn.data = [removeServer:true]
                rtn.msg = 'instance not found'
            }
        }
        catch(Throwable t) {
            log.error("removeWorkload error: ${t}", t)
            rtn.success = false
            rtn.msg = t.message
        }
        return rtn
    }

    @Override
    ServiceResponse resizeWorkload(Instance instance, Workload workload, ResizeRequest resizeRequest, Map opts) {
        def plan = resizeRequest.plan
        log.info("resizeContainer: ${instance}, ${workload}, ${plan}")
        ServiceResponse rtn = ServiceResponse.prepare()
        try {
            def server = morpheusContext.async.computeServer.get(workload.server.id).blockingGet()
            def amazonOpts = [container:workload]
            amazonOpts.server = server
            amazonOpts.zone = server.cloud
            amazonOpts.account = server.account
            amazonOpts.amazonClient = getAmazonRdsClient(workload.server.cloud, workload.server.region.regionCode)
            def serverVolSize = server.volumes?.first()?.maxStorage.div(ComputeUtility.ONE_GIGABYTE)
            def allocationSize
            if(resizeRequest.volumesUpdate) {
                def volOpts = resizeRequest.volumesUpdate.first()
                allocationSize = volOpts.size?.toLong()
            }
            if(plan?.id != instance.plan?.id || (allocationSize && allocationSize > serverVolSize)) {
                amazonOpts.instanceClass = plan.externalId
                if ((allocationSize && allocationSize > serverVolSize)) {
                    amazonOpts.diskSize = allocationSize
                }

                def resizeRdsResults = AmazonComputeUtility.resizeRdsInstance(amazonOpts)
                rtn.success = resizeRdsResults.success
                if (!resizeRdsResults.success) {
                    rtn.msg = resizeRdsResults.msg
                }
            }
            else {
                rtn.success = rtue
            }
        }
        catch (Throwable t) {
            log.error("Error resizing amazon instance to ${plan.name}", t)
            rtn.success = false
            rtn.msg = "Error resizing amazon instance to ${plan.name} ${t.message}"
        }
        return rtn
    }

    @Override
    ServiceResponse<ProvisionResponse> getServerDetails(ComputeServer server) {
        ProvisionResponse rtn = new ProvisionResponse()
        def serverUuid = server.externalId
        if(server && server.externalId) {
            def amazonClient = getAmazonRdsClient(server.cloud, false, server.resourcePool.regionCode)
            Map serverDetails = AmazonComputeUtility.getRdsServerDetail([amazonClient:amazonClient, server:server])
            if(serverDetails.success && serverDetails.server) {
                rtn.externalId = serverUuid
                rtn.success = serverDetails.success
                rtn.privateIp = serverDetails.server.getEndpoint()?.getAddress()
                rtn.publicIp = rtn.privateIp
                return ServiceResponse.success(rtn)
            } else {
                return ServiceResponse.error("Server not ready/does not exist")
            }
        } else {
            return ServiceResponse.error("Could not find server uuid")
        }
    }

    @Override
    ServiceResponse createWorkloadResources(Workload workload, Map opts) {
        return ServiceResponse.success()
    }

    /**
     * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
     *
     * @return an implementation of the MorpheusContext for running Future based rxJava queries
     */
    @Override
    MorpheusContext getMorpheus() {
        return this.morpheusContext
    }

    /**
     * A unique shortcode used for referencing the provided provider. Make sure this is going to be unique as any data
     * that is seeded or generated related to this provider will reference it by this code.
     * @return short code string that should be unique across all other plugin implementations.
     */
    @Override
    String getCode() {
        return PROVISION_TYPE_CODE
    }

    /**
     * Provides the provider name for reference when adding to the Morpheus Orchestrator
     * NOTE: This may be useful to set as an i18n key for UI reference and localization support.
     *
     * @return either an English name of a Provider or an i18n based key that can be scanned for in a properties file.
     */
    @Override
    String getName() {
        return 'Amazon RDS'
    }

    @Override
    String getProvisionTypeCode() {
        return PROVISION_TYPE_CODE
    }

    @Override
    ServiceResponse stopServer(ComputeServer computeServer) {
        return null
    }

    @Override
    ServiceResponse startServer(ComputeServer computeServer) {
        return null
    }

    @Override
    Boolean canAddVolumes() {
        return true
    }

    @Override
    Boolean canCustomizeRootVolume() {
        return true
    }

    @Override
    Boolean supportsCustomServicePlans() {
        return false
    }

    @Override
    Boolean hasNodeTypes() {
        return true
    }

    @Override
    Boolean customSupported() {
        return false
    }

    @Override
    Boolean lvmSupported() {
        return true
    }

    @Override
    HostType getHostType() {
        return HostType.vm
    }

    @Override
    String serverType() {
        return 'ami'
    }

    @Override
    String getViewSet() {
        return 'rdsCustom'
    }

    @Override
    Boolean requiresVirtualImage() {
        return false
    }

    @Override
    Boolean supportsAgent() {
        return false
    }

    @Override
    Boolean multiTenant() {
        return false
    }

    @Override
    Boolean aclEnabled() {
        return false
    }

    @Override
    Boolean canResizeRootVolume() {
        return true
    }

    @Override
    String getHostDiskMode() {
        return 'single'
    }

    /**
     * Specifies which deployment service should be used with this provider. In this case we are using the vm service
     * @return the name of the service
     */
    @Override
    String getDeployTargetService() {
        return "vmDeployTargetService"
    }

    /**
     * Specifies what format of nodes are created by this provider. In this case we are using the vm format
     * @return the name of the format
     */
    @Override
    String getNodeFormat() {
        return "vm"
    }

    /**
     * For the RDS provision provider, we do NOT want an RDS instance type created
     * @return
     */
    @Override
    Boolean createDefaultInstanceType() {
        return false
    }

    /**
     * Do instances provisioned by this provider support security groups
     * @return
     */
    @Override
    Boolean hasSecurityGroups() {
        return true
    }

    @Override
    Boolean hasComputeZonePools() {
        return true
    }

    @Override
    Boolean computeZonePoolRequired() {
        return true
    }

    @Override
    Collection<ServicePlan> getServicePlans() {
        def plans = []
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m4.large',
            editable:false,
            name:'RDS db.m4.large - 2 Core, 8GB Memory',
            description:'RDS db.m4.large - 2 Core, 8GB Memory',
            sortOrder:250,
            externalId:'db.m4.large',
            maxCores:2,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(8l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m4'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m4.xlarge',
            editable:false,
            name:'RDS db.m4.xlarge - 4 Core, 13GB Memory',
            description:'RDS db.m4.xlarge - 4 Core, 13GB Memory',
            sortOrder:251,
            externalId:'db.m4.xlarge',
            maxCores:4,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(13l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m4'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m4.2xlarge',
            editable:false, name:'RDS db.m4.2xlarge - 8 Core, 25.5GB Memory',
            description:'RDS db.m4.2xlarge - 8 Core, 25.5GB Memory',
            sortOrder:252,
            externalId:'db.m4.2xlarge',
            maxCores:8,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(25.5d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m4'
        )
        plans << new ServicePlan(
            code:'aws.plugin.rds-db.m4.4xlarge',
            editable:false,
            name:'RDS db.m4.4xlarge - 16 Core, 53.5GB Memory',
            description:'RDS db.m4.4xlarge - 16 Core, 53.5GB Memory',
            sortOrder:253,
            externalId:'db.m4.4xlarge',
            maxCores:16,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(53.5d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m4'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m4.10xlarge',
            editable:false,
            name:'RDS db.m4.10xlarge - 40 Core, 124.5GB Memory',
            description:'RDS db.m4.10xlarge - 40 Core, 124.5GB Memory',
            sortOrder:254,
            externalId:'db.m4.10xlarge',
            maxCores:40,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(123.5d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m4'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m3.medium',
            editable:false,
            name:'RDS db.m3.medium - 1 Core, 3.75GB Memory',
            description:'RDS db.m3.medium - 1 Core, 3.75GB Memory',
            sortOrder:255,
            externalId:'db.m3.medium',
            maxCores:1,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(3.75d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m3.large',
            editable:false,
            name:'RDS db.m3.large - 2 Core, 7.5GB Memory',
            description:'RDS db.m3.large - 2 Core, 7.5GB Memory',
            sortOrder:256,
            externalId:'db.m3.large',
            maxCores:2,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(7.5d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m3.xlarge',
            editable:false,
            name:'RDS db.m3.xlarge - 4 Core, 15GB Memory',
            description:'RDS db.m3.xlarge - 4 Core, 15GB Memory',
            sortOrder:257,
            externalId:'db.m3.xlarge',
            maxCores:4,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(15l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.m3.2xlarge',
            editable:false,
            name:'RDS db.m3.2xlarge - 8 Core, 30GB Memory',
            description:'RDS db.m3.2xlarge - 8 Core, 30GB Memory',
            sortOrder:258,
            externalId:'db.m3.2xlarge',
            maxCores:8,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(30l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'m3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.r3.large',
            editable:false,
            name:'RDS db.r3.large - 2 Core, 15GB Memory',
            description:'RDS db.r3.large - 2 Core, 15GB Memory',
            sortOrder:259,
            externalId:'db.r3.large',
            maxCores:2,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(15l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'r3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.r3.xlarge',
            editable:false, name:'RDS db.r3.xlarge - 4 Core, 30.5GB Memory',
            description:'RDS db.r3.xlarge - 4 Core, 30.5GB Memory',
            sortOrder:260,
            externalId:'db.r3.xlarge',
            maxCores:4,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(30.5d * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'r3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.r3.2xlarge',
            editable:false,
            name:'RDS db.r3.2xlarge - 8 Core, 61GB Memory',
            description:'RDS db.r3.2xlarge - 8 Core, 61GB Memory',
            sortOrder:261,
            externalId:'db.r3.2xlarge',
            maxCores:8,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(61l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'r3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.r3.4xlarge',
            editable:false, name:'RDS db.r3.4xlarge - 16 Core, 122GB Memory',
            description:'RDS db.r3.4xlarge - 16 Core, 122GB Memory',
            sortOrder:262,
            externalId:'db.r3.4xlarge',
            maxCores:16, maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(122l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'r3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.r3.8xlarge',
            editable:false,
            name:'RDS db.r3.8xlarge - 32 Core, 244GB Memory',
            description:'RDS db.r3.8xlarge - 32 Core, 244GB Memory',
            sortOrder:263,
            externalId:'db.r3.8xlarge',
            maxCores:32,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(244l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'r3'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.t2.micro',
            editable:false,
            name:'RDS db.t2.micro -  1 Core, 1GB Memory',
            description:'RDS db.t2.micro -  1 Core, 1GB Memory',
            sortOrder:264,
            externalId:'db.t2.micro',
            maxCores:1,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(1l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'t2'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.t2.small',
            editable:false,
            name:'RDS db.t2.small - 1 Core, 2GB Memory',
            description:'RDS db.t2.small - 1 Core, 2GB Memory',
            sortOrder:265,
            externalId:'db.t2.small',
            maxCores:1,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(2l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'t2'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.t2.medium',
            editable:false,
            name:'RDS db.t2.medium - 2 Core, 4GB Memory',
            description:'RDS db.t2.medium - 2 Core, 4GB Memory',
            sortOrder:266,
            externalId:'db.t2.medium',
            maxCores:2, maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(4l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'t2'
        )
        plans << new ServicePlan(
            code:'plugin.aws.rds-db.t2.large',
            editable:false,
            name:'RDS db.t2.large - 2 Core, 8GB Memory',
            description:'RDS db.t2.large - 2 Core, 8GB Memory',
            sortOrder:267,
            externalId:'db.t2.large',
            maxCores:2,
            maxStorage:5l * 1024l * 1024l * 1024l,
            maxMemory:(long)(8l * 1024l * 1024l * 1024l),
            active:true,
            customMaxStorage:true,
            customMaxDataStorage:false,
            addVolumes:false,
            serverClass:'t2'
        )
        return plans
    }

    @Override
    Collection<StorageVolumeType> getRootVolumeStorageTypes() {
        return getStorageVolumeTypes()
    }

    @Override
    Collection<StorageVolumeType> getDataVolumeStorageTypes() {
        return getStorageVolumeTypes()
    }

    protected buildWorkloadRunConfig(Workload workload, WorkloadRequest workloadRequest, Map opts) {
        def refDataSvc = morpheus.async.referenceData
        Map workloadConfig = workload.getConfigMap()
        ComputeServer server = workload.server
        Cloud cloud = server.cloud

        def cloudConfig = cloud.getConfigMap()
        def dbSubnetGroupKeyValue = workloadConfig.dbSubnetGroup
        StorageVolume rootVolume = server.volumes?.find{it.rootVolume == true}
        def refDataItems = []
        refDataSvc.listByCategoryAndKeyValue("amazon.ec2.db.subnetgroup.${cloud.id}.%", dbSubnetGroupKeyValue).blockingSubscribe() { item ->
            refDataItems << item
        }
        def dbSubnetGroup = refDataItems.size() ? refDataItems.first() : null
        if(dbSubnetGroup) {
            log.info("DB SUbnet Group Selected ${dbSubnetGroup.category}")
            String region = dbSubnetGroup.category.replace("amazon.ec2.db.subnetgroup.${cloud.id}.",'')
            opts.region = region
            log.info("Getting Region: ${region}")
            if(region) {
                def regionModel = morpheus.async.cloud.region.findByCloudAndRegionCode(cloud.id, region).blockingGet().get()
                server.region = regionModel
                for (vol in server.volumes) {
                    vol.regionCode = regionModel
                }
            }
        }

        def flavorId = workload.instance.plan.externalId
        def maxStorage = rootVolume.maxStorage

        //set the compute server type
        server.serverOs = server.serverOs ?: server.sourceImage?.osType
        server.osType = (server.sourceImage?.osType?.platform == 'windows' ? 'windows' : 'linux') ?: server.sourceImage?.platform
        def newType = morpheus.async.cloud.findComputeServerTypeByCode('amazonRdsVm').blockingGet()
        if(newType && server.computeServerType?.id != newType.id) {
            server.computeServerType = newType
        }
        //get the engine
        def dbEngine = refDataSvc.get(workloadConfig.dbEngineVersion.toLong()).blockingGet()
        def dbUsername = workload.instance.serviceUsername ?: workloadConfig.username ?: 'morpheus'
        def dbPassword = workload.instance.servicePassword ?: workloadConfig.password ?: 'morpheus'
        def runConfig = [amazonClient:opts.amazonClient, account:workload.account, name:workload.instance.name, vpcRef:cloudConfig.vpc,
            subnetRef:workloadConfig.subnetId, flavorRef:flavorId, zoneRef:cloudConfig.availabilityId,
            server:workload.server, zone:opts.zone, diskSize:maxStorage.div(ComputeUtility.ONE_GIGABYTE), dbEngine:dbEngine.xRef,
            dbSubnetGroup:workloadConfig.dbSubnetGroup, dbVersion:dbEngine.value, dbUsername:dbUsername,
            dbPassword:dbPassword, securityGroups:workloadConfig.securityGroups, databaseName:workloadConfig.initialDatabase,
            dbPublic:workloadConfig.dbPublic, dbBackupRetention:workloadConfig.dbBackupRetention]
        if(workload.workloadType.category == 'sqlserver') {
            runConfig.licenseMode = 'license-included'
        }

        return runConfig
    }

    protected getStorageVolumeTypes() {
        def volumeTypes = []

        volumeTypes << new StorageVolumeType([
            code: 'standard',
            name: 'Standard',
            volumeType: 'disk',
            description: 'Standard'
        ])

        return volumeTypes
    }

    protected getAmazonRdsClient(Cloud cloud, Boolean fresh = false, String region = null) {
        return this.@plugin.getAmazonRdsClient(cloud, fresh, region)
    }
}
