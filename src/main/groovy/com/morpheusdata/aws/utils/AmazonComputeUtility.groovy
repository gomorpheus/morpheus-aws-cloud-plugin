package com.morpheusdata.aws.utils

import com.amazonaws.client.builder.AwsClientBuilder
import com.amazonaws.retry.RetryMode
import com.amazonaws.retry.RetryPolicy
import com.amazonaws.services.certificatemanager.model.AddTagsToCertificateRequest
import com.amazonaws.services.certificatemanager.model.CertificateSummary
import com.amazonaws.services.certificatemanager.model.ImportCertificateRequest
import com.amazonaws.services.certificatemanager.model.ImportCertificateResult
import com.amazonaws.services.certificatemanager.model.ListCertificatesRequest
import com.amazonaws.services.certificatemanager.model.ListCertificatesResult
import com.amazonaws.services.certificatemanager.model.ListTagsForCertificateRequest
import com.amazonaws.services.certificatemanager.model.ListTagsForCertificateResult
import com.amazonaws.services.simplesystemsmanagement.model.GetParametersRequest
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.model.AccountCertificate
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.KeyPair
import com.morpheusdata.core.util.KeyUtility
import com.morpheusdata.core.util.ProgressInputStream
import com.morpheusdata.model.Network
import com.morpheusdata.model.AccountIntegration
import groovy.json.JsonOutput
import groovy.util.logging.Slf4j

import java.nio.ByteBuffer
import java.text.SimpleDateFormat

import com.bertramlabs.plugins.karman.StorageProvider as KarmanProvider
import javax.crypto.*
import java.security.*
import java.security.spec.*
import java.nio.charset.Charset
import org.bouncycastle.util.encoders.Base64

//aws auth
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.*
//ec2
import com.amazonaws.services.ec2.*
import com.amazonaws.services.ec2.model.*
//rds
import com.amazonaws.services.rds.*
import com.amazonaws.services.rds.model.*
//route53
import com.amazonaws.services.route53.*
import com.amazonaws.services.route53.model.*
//elb
import com.amazonaws.services.elasticloadbalancingv2.*
import com.amazonaws.services.elasticloadbalancingv2.model.*
//auto scaling
import com.amazonaws.services.autoscaling.*
import com.amazonaws.services.autoscaling.model.*
//elastic search
import com.amazonaws.services.elasticsearch.*
import com.amazonaws.services.elasticsearch.model.*
//certs
import com.amazonaws.services.certificatemanager.*

//security token
import com.amazonaws.services.securitytoken.*
import com.amazonaws.services.securitytoken.model.*
//cloud watch
import com.amazonaws.services.cloudwatch.*
import com.amazonaws.services.cloudwatch.model.*
//cloud formation
import com.amazonaws.services.cloudformation.*
import com.amazonaws.services.cloudformation.model.*
//iam
import com.amazonaws.services.identitymanagement.*
import com.amazonaws.services.identitymanagement.model.*
//eks imports
import com.amazonaws.services.eks.*
import com.amazonaws.services.eks.model.*
//orgs
import com.amazonaws.services.organizations.*
import com.amazonaws.services.organizations.model.* //DetachPolicyRequest
//orgs
import com.amazonaws.services.pricing.*
import com.amazonaws.services.pricing.model.*
//costing
import com.amazonaws.services.costexplorer.*
import com.amazonaws.services.costexplorer.model.*
import com.amazonaws.services.costandusagereport.*
import com.amazonaws.services.costandusagereport.model.*
//s3
import com.amazonaws.services.s3.*
import com.amazonaws.services.s3.model.*
//ssm
import com.amazonaws.services.simplesystemsmanagement.*
import com.amazonaws.services.simplesystemsmanagement.model.*

@Slf4j
class AmazonComputeUtility {

	static diskNames = ['/dev/sda', '/dev/sdf', '/dev/sdg', '/dev/sdh', '/dev/sdi', '/dev/sdj', '/dev/sdk', '/dev/sdl']
	static volumeDevices = ['/dev/sdb','/dev/sdc','/dev/sdd','/dev/sde','/dev/sdf', '/dev/sdg', '/dev/sdh', '/dev/sdi', '/dev/sdj', '/dev/sdk', '/dev/sdl']

	static cipherTypeList = ['RSA/NONE/PKCS1Padding', 'RSA/ECB/PKCS1Padding', 'RSA/ECB/OAEPWithSHA-1AndMGF1Padding', 'RSA/ECB/OAEPWithSHA-256AndMGF1Padding']

	// load balancer constants
	static final String LOAD_BALANCER_SCHEME_INTERNAL = 'internal'
	static final String LOAD_BALANCER_SCHEME_INTERNET_FACING = 'Internet-facing'

	static class InvalidCredentialsRequestHandler extends com.amazonaws.handlers.RequestHandler2 {
		@Override
		public void afterAttempt(com.amazonaws.handlers.HandlerAfterAttemptContext context) {
			if(context.exception instanceof com.amazonaws.AmazonServiceException && (context.exception?.statusCode == 401 || context.exception?.errorCode == 'AuthFailure')) {
				//fast fail - prevent unnecessary retries for invalid creds
				throw context.exception
			}
		}
	}

	static testConnection(Cloud cloud) {
		def rtn = [success:false, invalidLogin:false]
		def hasCredentials = false
		try { hasCredentials = getAmazonAccessKey(cloud) && getAmazonSecretKey(cloud) } catch(e) {}
		try {
			if(hasCredentials) {
				def endpoint = getAmazonEndpoint(cloud)
				def endpointConfiguration = new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(endpoint, getAmazonEndpointRegion(endpoint))
				def clientConfiguration = getClientConfiguration(cloud)
				def amazonClient = AmazonEC2Client.builder()
					.withCredentials(getAmazonCredentials(cloud, clientConfiguration).credsProvider)
					.withClientConfiguration(clientConfiguration)
					.withRequestHandlers(new InvalidCredentialsRequestHandler())
					.withEndpointConfiguration(endpointConfiguration)
					.build()

				def vpcRequest = new DescribeVpcsRequest()
				rtn.vpcList = amazonClient.describeVpcs(vpcRequest).getVpcs()
				rtn.amazonClient = amazonClient
				rtn.success = true
			}
		} catch(com.amazonaws.AmazonServiceException awse) {
			log.error("testConnection to amazon: ${awse}", awse)
			rtn.invalidLogin = (awse.errorCode == 'AuthFailure')
		} catch(e) {
			log.error("testConnection to amazon: ${e}", e)
		}
		return rtn
	}

	static testConnection(AccountIntegration accountIntegration, Cloud cloud) {
		if(cloud) {
			return testConnection(cloud)
		}
		// test standalone integration (ie. route53)
		def rtn = [success:false, invalidLogin:false]
		try {
			def endpoint = getAmazonEndpoint(accountIntegration)
			def region = getAmazonEndpointRegion(endpoint)
			def endpointConfiguration = new com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration(endpoint, region)
			def authConfig = [:]
			authConfig.accessKey = accountIntegration.credentialData?.username ?: accountIntegration.serviceUsername
			authConfig.secretKey = accountIntegration.credentialData?.password ?: accountIntegration.servicePassword
			authConfig.region = region
			def clientConfiguration = getClientConfiguration(authConfig)
			def amazonClient = AmazonEC2Client.builder()
				.withCredentials(getAmazonCredentials(authConfig, clientConfiguration).credsProvider)
				.withClientConfiguration(clientConfiguration)
				.withRequestHandlers(new InvalidCredentialsRequestHandler())
				.withEndpointConfiguration(endpointConfiguration)
				.build()
			def vpcRequest = new DescribeVpcsRequest()
			rtn.vpcList = amazonClient.describeVpcs(vpcRequest).getVpcs()
			rtn.amazonClient = amazonClient
			rtn.success = true
		} catch(com.amazonaws.AmazonServiceException awse) {
			log.error("testConnection to amazon: {}", awse, awse)
			rtn.invalidLogin = (awse.errorCode == 'AuthFailure')
		} catch(e) {
			log.error("testConnection to amazon: {}", e, e)
		}
		return rtn
		
	}

	static createServer(opts) {
		log.debug("createServer opts: ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def createServer = new RunInstancesRequest()
			def imageRef = opts.imageRef
			def flavorRef = opts.flavorRef
			def zoneRef = opts.zoneRef
			def vpcRef = opts.vpcRef
			def subnetRef = opts.subnetRef
			def count = opts.count ?: 1
			def keyName = opts.publicKeyName
			def securityList = new LinkedList<String>()
			def diskType = opts.osDiskType ?: 'gp2'
			//general config
			createServer.withImageId(imageRef).withInstanceType(flavorRef).withMinCount(count).withMaxCount(count)
				.withPlacement(new Placement(zoneRef)).withKeyName(keyName)
			//security groups
			if(opts.securityGroups?.size() > 0) {
				opts.securityGroups?.each { securityGroup ->
					securityList.add(securityGroup.id)
				}
			} else if(opts.securityRef) {
				securityList.add(opts.securityRef)
			}
			//iam spec
			if(opts.instanceProfile) {
				createServer.withIamInstanceProfile(new IamInstanceProfileSpecification().withArn(opts.instanceProfile))
			}
			def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', opts.name ?: "morpheus node")
			def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			def volTagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			tagList.add(nameTag)
			HashSet<String> tagNames = new HashSet<>()
			tagNames.add('Name')

			buildAdditionalTags(opts.tagList, tagList, tagNames)
			buildAdditionalTags(opts.tagList, volTagList, new HashSet<>())

			TagSpecification tagSpecification = new TagSpecification()
			tagSpecification.withResourceType(ResourceType.Instance)
			tagSpecification.setTags(tagList)

			if(volTagList.size() > 0){
				TagSpecification volumeTagSpecification = new TagSpecification()
				volumeTagSpecification.withResourceType(ResourceType.Volume)
				volumeTagSpecification.setTags(volTagList)
				createServer.withTagSpecifications(tagSpecification,volumeTagSpecification)
			} else {
				createServer.withTagSpecifications(tagSpecification)
			}
			//cloud config
			if(opts.cloudConfig)
				createServer.withUserData(opts.cloudConfig.bytes.encodeBase64().toString())
			//setup the primary network interface
			if(opts.networkConfig?.primaryInterface?.network?.externalId) {
				def awsNic = new InstanceNetworkInterfaceSpecification()
				awsNic.subnetId = opts.networkConfig.primaryInterface.externalId ?: subnetRef
				awsNic.groups = securityList
				awsNic.deviceIndex = 0
				awsNic.deleteOnTermination = true
				//assign a public ip
				awsNic.associatePublicIpAddress = opts.networkConfig.primaryInterface.assignPublic
				//assign a static
				if(opts.networkConfig.primaryInterface.doStatic)
					awsNic.privateIpAddress = opts.networkConfig.primaryInterface.ipAddress
				createServer.withNetworkInterfaces([awsNic])
			} else if(subnetRef?.length() > 0) {
				createServer.withSubnetId(subnetRef)
				createServer.withSecurityGroupIds(securityList)
			}
			//disks
			def diskList = new LinkedList<BlockDeviceMapping>()
			log.debug("createServer osDiskSize: ${opts.osDiskSize}")
			if(opts.osDiskSize) {
				EbsBlockDevice rootDisk = new EbsBlockDevice()
				opts += addDiskOpts(diskType, opts.osDiskSize.toInteger(), opts)
				rootDisk.withVolumeType(diskType).withVolumeSize(opts.osDiskSize.toInteger())
				log.debug("createServer osDiskSnapshot: ${opts.osDiskSnapshot}, imageRef: ${imageRef}")
				if(opts.osDiskSnapshot) {
					rootDisk.withSnapshotId(opts.osDiskSnapshot)
				}
				if(opts.iops && diskType == 'io1')
					rootDisk.iops = opts.iops
				if(opts.kmsKeyId) {
					rootDisk.setEncrypted(true)
					rootDisk.withKmsKeyId(opts.kmsKeyId)
				}
				diskList.add(new BlockDeviceMapping().withEbs(rootDisk).withDeviceName(opts.osDiskName ?: '/dev/sda1'))
			}
			if(opts.diskList?.size() > 0) {
				opts.diskList?.each { disk ->
					def dataDisk = new EbsBlockDevice()
					disk += addDiskOpts(disk.diskType, disk.diskSize.toInteger(), disk)
					dataDisk.withVolumeType(disk.diskType).withVolumeSize(disk.diskSize.toInteger())
					if(disk.diskSnapshot) {
						dataDisk.withSnapshotId(opts.diskSnapshot)
					} else {
						dataDisk.withEncrypted(opts.encryptEbs ? true : false)
					}
					if(disk.iops && disk.diskType == 'io1')
						dataDisk.iops = disk.iops
					if(opts.kmsKeyId) {
						dataDisk.setEncrypted(true)
						dataDisk.withKmsKeyId(opts.kmsKeyId)
					}
					diskList.add(new BlockDeviceMapping().withEbs(dataDisk).withDeviceName(disk.deviceName))
				}
			}
			//add disks
			createServer.withBlockDeviceMappings(diskList)
			//launch it
			rtn.results = amazonClient.runInstances(createServer)
			rtn.reservation = rtn.results?.getReservation()
			def tmpInstances = rtn.reservation?.getInstances()
			rtn.server = tmpInstances?.size() > 0 ? tmpInstances.get(0) : null
			rtn.externalId = rtn.server.getInstanceId()
			if(rtn.server) {
				rtn.volumes = getVmVolumes(rtn.server)
				rtn.networks = getVmNetworks(rtn.server)
			}
			log.debug("createServer results: ${rtn.results}")
			// //tag it
			// def nameTag = new Tag('Name', opts.name ?:"morpheus node")
			// def resourceList = new LinkedList<String>()
			// resourceList.add(rtn.server.getInstanceId())
			// def tagList = new LinkedList<Tag>()
			// tagList.add(nameTag)
			// opts.tagList?.each {
			// 	def newTag = new Tag(it.name, it.value)
			// 	tagList.add(newTag)
			// }
			// def tagRequest = new CreateTagsRequest(resourceList, tagList)
			// def tagResults = amazonClient.createTags(tagRequest)
			rtn.success = true
		} catch(e) {
			log.error("createServer error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static applyEc2Tags(opts) {
		def rtn = [success: false]
		try {
			def amazonClient = opts.amazonClient
			def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', opts.name ?: "morpheus node")
			def resourceList = new LinkedList<String>()
			resourceList.add(opts.server?.externalId ?: opts.serverId)
			
			def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			tagList.add(nameTag)
			opts.tagList?.each {
				def newTag = new com.amazonaws.services.ec2.model.Tag(it.name, it.value)
				tagList.add(newTag)
			}
			def tagRequest = new CreateTagsRequest(resourceList, tagList)
			def tagResults = amazonClient.createTags(tagRequest)

			//now do volumes
			if(opts.resources) {
				resourceList = new LinkedList<String>()
				
				if(opts.resources) {
					resourceList += opts.resources
				}
				tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
				opts.tagList?.findAll{it.name.toLowerCase() != 'name'}?.each {
					def newTag = new com.amazonaws.services.ec2.model.Tag(it.name, it.value)
					tagList.add(newTag)
				}
				tagRequest = new CreateTagsRequest(resourceList, tagList)
				tagResults = amazonClient.createTags(tagRequest)
			}
			rtn.success = true
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				//not found - no big deal
				log.warn("applyEc2Tags - instance not found: ${awsError}")
			} else {
				log.error("applyEc2Tags error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("applyEc2Tags error: ${e}", e)
			rtn.msg = e.message
		}
	}

	static removeEc2Tags(opts) {
		def rtn = [success: false]
		try {
			def amazonClient = opts.amazonClient
			def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', opts.name ?:"morpheus node")
			def resourceList = new LinkedList<String>()
			resourceList.add(opts.server?.externalId ?: opts.serverId)
			if(opts.resources) {
				resourceList += opts.resources
			}
			def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			tagList.add(nameTag)
			opts.tagList?.each {
				def newTag = new com.amazonaws.services.ec2.model.Tag(it.name, it.value)
				tagList.add(newTag)
			}
			DeleteTagsRequest tagRequest =  new DeleteTagsRequest(resourceList)
			tagRequest.setTags(tagList)
			//def tagRequest = new CreateTagsRequest(resourceList, tagList)
			def tagResults = amazonClient.deleteTags(tagRequest)
			rtn.success = true
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				//not found - no big deal
				log.warn("removeEc2Tags - instance not found: ${awsError}")
			} else {
				log.error("removeEc2Tags error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("removeEc2Tags error: ${e}", e)
			rtn.msg = e.message
		}
	}

	static createRdsServer(opts) {
		//MySQL | mariadb | oracle-se1 | oracle-se | oracle-ee | sqlserver-ee | sqlserver-se | sqlserver-ex | sqlserver-web | postgres | aurora
		def rtn = [success:false]
		try {
			log.info("Create RDS Server (Debug for more details)")
			log.debug("createRdsServer: ${opts}")
			def amazonClient = opts.amazonClient
			def createServer = new CreateDBInstanceRequest()
			def securityList = new LinkedList<String>()
			def dbBackupRetention = opts.dbBackupRetention ? opts.dbBackupRetention.toInteger() : 1
			if(opts.securityGroups?.size() > 0) {
				opts.securityGroups?.each { securityGroup ->
					securityList.add(securityGroup.id)
				}
			} else if(opts.securityRef) {
				securityList.add(opts.securityRef)
			}
			createServer.setEngine(opts.dbEngine ?: 'MySQL')
			createServer.setLicenseModel(opts.licenseMode ?: 'general-public-license')
			createServer.setEngineVersion(opts.dbVersion ?: '5.6')
			createServer.setDBInstanceClass(opts.flavorRef)
			createServer.setMultiAZ(false)
			createServer.setAutoMinorVersionUpgrade(true)
			createServer.setDBInstanceIdentifier(opts.name)

			if(opts.dbEngine == "aurora-mysql") {
				log.debug("RDS Server is MySQL Aurora, creating DB cluster first")
				def createCluster = new CreateDBClusterRequest()
				createCluster.setDBClusterIdentifier(opts.name)
				createCluster.setEngine(opts.dbEngine)
				createCluster.setMasterUsername(opts.dbUsername)
				createCluster.setMasterUserPassword(opts.dbPassword)
				createCluster.setBackupRetentionPeriod(dbBackupRetention)
				createCluster.setDBSubnetGroupName(opts.dbSubnetGroup)
				createCluster.setVpcSecurityGroupIds(securityList)

				createCluster.setEngineVersion(opts.dbVersion ?: '5.6')
				try {
					amazonClient.createDBCluster(createCluster)
					createServer.setDBClusterIdentifier(opts.name)
				} catch (e) {
					//if cluster already exists, reuse
					if(e.errorCode == "DBClusterAlreadyExistsFault") {
						createServer.setDBClusterIdentifier(opts.name)
					} else {
						throw e
					}
				}
			} else {
				createServer.setMasterUsername(opts.dbUsername)
				createServer.setMasterUserPassword(opts.dbPassword)
				createServer.setBackupRetentionPeriod(dbBackupRetention)
				createServer.setAllocatedStorage(opts.diskSize.toInteger())
				createServer.setVpcSecurityGroupIds(securityList)
			}
			createServer.setDBName(opts.databaseName)
			createServer.setPubliclyAccessible((opts.dbPublic == 'on' || opts.dbPublic == true || opts.dbPublic == 'true'))
			//createServer.setPort(3306)
			//createServer.setDBParameterGroupName("default.mysql5.5")
			//if(opts.subnetRef?.length() > 0)
			createServer.setDBSubnetGroupName(opts.dbSubnetGroup)
			rtn.results = amazonClient.createDBInstance(createServer)
			rtn.server = rtn.results
		log.debug("got: ${rtn.results}")
		rtn.success = true
		} catch(e) {
			log.error("createRdsServer error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static uploadKeypair(opts) {
		def rtn = [success:false]
		try {
			def doUpload = true
			if(opts.keyName || opts.fingerprint) {
				def keyLookup = loadKeypair(opts)
				if(keyLookup.success == true && keyLookup.found == true) {
					doUpload = false
					rtn.success = true
					rtn.keyName = keyLookup.keyMatch?.keyName ?: opts.keyName
					rtn.uploaded = false
				}
			}
			if(doUpload == true) {
				def amazonClient = opts.amazonClient
				def key = opts.key
				def keyName = opts.keyName ?: generateKeyName(opts.zone.id)
				def keyRequest = new ImportKeyPairRequest(keyName, key.publicKey)
				amazonClient.importKeyPair(keyRequest)
				rtn.success = true
				rtn.keyName = keyName
				rtn.uploaded = true
			}
		} catch(e) {
			log.error("uploadKeypair error:${e}", e)
		}
		return rtn
	}

	static deleteKeypair(opts) {
		def rtn = [success:false]
		try {
			if(opts.keyName) {
				def amazonClient = opts.amazonClient
				
				def keyRequest = new DeleteKeyPairRequest()
				keyRequest.withKeyName(opts.keyName)
				
				def keyResult = amazonClient.deleteKeyPair(keyRequest)
				rtn.success = true
				
			}
		} catch(e) {
			log.warn("removeKeypair error:${e}", e)
		}
		return rtn
	}

	static loadKeypair(opts) {
		def rtn = [success:false]
		try {
			if(opts.keyName || opts.fingerprint) {
				def amazonClient = opts.amazonClient
				def keyRequest = new DescribeKeyPairsRequest()

				if(opts.keyName)
					keyRequest.withKeyNames([opts.keyName])
				if(opts.fingerprint)
					keyRequest.withFilters(new Filter('fingerprint', [opts.fingerprint]))

				def keyResult = amazonClient.describeKeyPairs(keyRequest)
				rtn.success = true
				rtn.keyPairs = keyResult.getKeyPairs()
				rtn.keyMatch = rtn.keyPairs.find{ it.keyName == opts.keyName || it.keyFingerprint == opts.fingerprint }
				rtn.found = rtn.keyMatch != null
			}
		} catch(e) {
			// We get an exception if the keypair is not found.. not necessarily an error
			log.debug("loadKeypair error:${e}", e)
		}
		return rtn
	}

	static listKeypairs(opts) {
		def rtn = [success:false]
		try {
			def keyResults = opts.amazonClient.describeKeyPairs()
			rtn.success = true
			rtn.keyPairs = keyResults.getKeyPairs()
		} catch(e) {
			log.error("listKeypairs error:${e}", e)
		}
		return rtn
	}

	static listImages(opts) {
		def rtn = [success:false, imageList:[]]
		try {
			def amazonClient = opts.amazonClient
			def amazonSystemsManagementClient = opts.amazonSystemsManagementClient
			def imageRequest = new DescribeImagesRequest().withFilters(new LinkedList<Filter>())
			if(!opts.allImages) {
				imageRequest.getFilters().add(new Filter().withName("is-public").withValues("false"))
			}
			if(opts.imageIds) {
				if(opts.imageIds instanceof String && opts.imageIds.contains('/')) {
					// First need to fetch it via the path
					def getParametersRequest = new GetParametersRequest().withNames(opts.imageIds)
					def getParametersResponse = amazonSystemsManagementClient.getParameters(getParametersRequest)
					def resolvedAmiId = getParametersResponse.getParameters().getAt(0).getValue()
					def imageId = resolvedAmiId
					imageRequest.withImageIds(imageId)
				} else {
					imageRequest.withImageIds(opts.imageIds)
				}
			}
			def response = amazonClient.describeImages(imageRequest)
			rtn.imageList = response.getImages()
			rtn.success = true
		} catch(e) {
			log.debug("listImages error: ${e}", e)
		}
		return rtn
	}

	static listSnapshotImages(opts) {
		def rtn = [success:false, imageList:[]]
		try {
			def amazonClient = opts.amazonClient
			def imageRequest = new DescribeImagesRequest().withFilters(new LinkedList<Filter>())
			imageRequest.getFilters().add(new Filter().withName("is-public").withValues("false"))
			imageRequest.getFilters().add(new Filter().withName("block-device-mapping.snapshot-id").withValues(opts.snapshotId))
			rtn.imageList =  amazonClient.describeImages(imageRequest).getImages()
			rtn.success = true
		} catch(e) {
			log.debug("listSnapshotImages error: ${e}", e)
		}
		return rtn
	}

	static deregisterImage(opts){
		log.info("deregisterImage ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def deregisterImageRequest = new DeregisterImageRequest().withImageId(opts.imageId)
			def deregisterImageResult = amazonClient.deregisterImage(deregisterImageRequest)
			rtn.success = true
		} catch(e) {
			rtn.msg = "Error in deregister image: ${e.message}"
			log.debug("deregisterImage error: ${e}", e)
		}
		return rtn
	}

	static loadImage(opts) {
		def rtn = [success:false, imageList:[], image:null]
		try {
			def amazonClient = opts.amazonClient
			def imageRequest
			if(opts.imageName && !opts.imageId) {
				imageRequest = new DescribeImagesRequest()
				if(opts.isPublic) {
					// imageRequest = imageRequest.withOwners(['amazon','aws-marketplace'])
				}
				imageRequest = imageRequest.withFilters(new LinkedList<Filter>())
				imageRequest.getFilters().add(new Filter().withName("name").withValues(opts.imageName))
				log.info("Searching for Image with name: ${opts.imageName}")
			} else {
				imageRequest = new DescribeImagesRequest().withImageIds(new LinkedList<String>())
				imageRequest.getImageIds().add(opts.imageId)
			}
			rtn.imageList =  amazonClient.describeImages(imageRequest).getImages()
			log.info("Image List: ${rtn.imageList}")
			log.debug("got: ${rtn.imageList}")
			rtn.image = rtn.imageList?.size() > 0 ? rtn.imageList[0] : null
			rtn.success = true
		} catch(e) {
			log.debug("loadImage error: ${e}", e)
		}
		return rtn
	}

	static listFlavors(opts) {
		def rtn = []
		com.amazonaws.services.ec2.model.InstanceType.values()?.each { value ->
			rtn << [id:value.toString(), name:value.toString()]
		}
		return rtn
	}

	static listAvailabilityZones(opts) {
		def rtn = [success:false, zoneList:[]]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient as AmazonEC2Client
			def zoneRequest = new DescribeAvailabilityZonesRequest()
			rtn.zoneList =  amazonClient.describeAvailabilityZones(zoneRequest).getAvailabilityZones()
			rtn.success = true
		} catch(e) {
			log.debug("listAvailabilityZones error: ${e}", e)
		}
		return rtn
	}

	static listSecurityGroups(opts) {
		def rtn = [success:false, securityList:[]]
		try {
			def amazonClient = opts.amazonClient
			def vpcId = opts.zone.getConfigProperty('vpc')
			def securityRequest = new DescribeSecurityGroupsRequest().withFilters(new LinkedList<Filter>())
			if(vpcId) {
				securityRequest.getFilters().add(new Filter().withName("vpc-id").withValues(vpcId))
			}
			rtn.securityList =  amazonClient.describeSecurityGroups(securityRequest).getSecurityGroups()
			rtn.success = true
		} catch(e) {
			log.debug("listSecurityGroups error: ${e}", e)
		}
		return rtn
	}

	static getSecurityGroup(opts) {
		def rtn = [success:false, securityGroup:[]]
		try {
			def amazonClient = opts.amazonClient
			def groupId = opts.externalId
			DescribeSecurityGroupsRequest request = new DescribeSecurityGroupsRequest().withGroupIds(groupId)
			DescribeSecurityGroupsResult results = amazonClient.describeSecurityGroups(request)
			if(results.securityGroups) {
				rtn.securityGroup = results.securityGroups.first()
			}
			rtn.success = true
		} catch(e) {
			log.debug("getSecurityGroup error: ${e}", e)
		}
		return rtn
	}

	static setServerSecurityGroups(opts) {
		log.debug "setServerSecurityGroups : $opts"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			ModifyInstanceAttributeRequest setGroupsRequest = new ModifyInstanceAttributeRequest()
					.withInstanceId(opts.server.externalId)
					.withGroups(new LinkedList<String>())
			opts.groupIds?.each { setGroupsRequest.getGroups().add(it) }
			amazonClient.modifyInstanceAttribute(setGroupsRequest)
			rtn.success = true
		} catch(e) {
			log.error("setServerSecurityGroups error: ${e}", e)
		}
		return rtn
	}

	static setInstanceAttributes(Map opts, String instanceId, Collection provisionArguments) {
		log.debug("setInstanceAttributes: ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def attributeRequest = new ModifyInstanceAttributeRequest()
			attributeRequest.setInstanceId(instanceId)
			provisionArguments.each { row -> 
				if(row.argumentType?.externalType == 'attribute') {
					def rowValue = row.getArgumentValue()
					def rowSetCall = 'set' + row.argumentType.argument.capitalize()
					attributeRequest."${rowSetCall}"(rowValue)
				}
			}
			def results = amazonClient.modifyInstanceAttribute(attributeRequest)
			rtn.success = true
		} catch(e) {
			log.error("setInstanceAttributes error: ${e}", e)
		}
		return rtn
	}

	static listVpcs(opts) {
		def rtn = [success:false, vpcList:[]]
		try {
			def amazonClient = opts.amazonClient
			def vpcRequest = new DescribeVpcsRequest()
			if(opts.filterVpcId) {
				vpcRequest.getFilters().add(new Filter().withName("vpc-id").withValues(opts.filterVpcId))
			}
			rtn.vpcList = amazonClient.describeVpcs(vpcRequest).getVpcs()
			rtn.success = true
		} catch(e) {
			log.debug("listVpcs error: ${e}", e)
		}
		return rtn
	}


	static listRegions(opts) {
		def rtn = [success:false, regionList:[]]
		try {
			AmazonEC2 amazonClient = opts.amazonClient as AmazonEC2
			DescribeRegionsRequest regionRequest = new DescribeRegionsRequest().withAllRegions(false)

			rtn.regionList = amazonClient.describeRegions(regionRequest).getRegions()

			rtn.success = true
		} catch(e) {
			log.debug("listRegions error: ${e}", e)
		}
		return rtn
	}

	static listInstanceTypes(opts) {
		def rtn = [success:false, instanceTypes:[]]
		try {
			def amazonClient = opts.amazonClient
			boolean done = false
			def request = new DescribeInstanceTypesRequest()
			if(opts.dryRun == true) {
				DryRunResult result = amazonClient.dryRun(request)
				rtn.success = result.isSuccessful()
			} else {
				request.getFilters().add(new Filter().withName())
				while(!done) {
					DescribeInstanceTypesResult result = amazonClient.describeInstanceTypes(request)
					rtn.instanceTypes += result.getInstanceTypes()
					request.setNextToken(result.getNextToken())
					if(result.getNextToken() == null) {
						done = true
					}
				}
				rtn.success = true
			}
		} catch(e) {
			log.debug("listInstanceTypes error: ${e}", e)
		}
		return rtn
	}
	
	static listRouteTables(opts) {
		def rtn = [success:false, routeTableList:[]]
		try {
			def amazonClient = opts.amazonClient
			def routeTablesRequest = new DescribeRouteTablesRequest()
			if(opts.filterVpcId) {
				routeTablesRequest.getFilters().add(new Filter().withName("vpc-id").withValues(opts.filterVpcId))
			}
			rtn.routeTableList = amazonClient.describeRouteTables(routeTablesRequest).getRouteTables()
			rtn.success = true
		} catch(e) {
			log.debug("listRouteTables error: ${e}", e)
		}
		return rtn
	}
	
	static createRoute(opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def routeRequest = new CreateRouteRequest()
			routeRequest.setRouteTableId(opts.routeTableId)
			switch(opts.destinationType) {
				case 'EGRESS_ONLY_INTERNET_GATEWAY':
					routeRequest.setEgressOnlyInternetGatewayId(opts.destination)
					routeRequest.setDestinationIpv6CidrBlock(opts.destinationCidrBlock)
					break
				case 'GATEWAY':
					routeRequest.setGatewayId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'INTERNET_GATEWAY':
					routeRequest.setGatewayId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'INSTANCE':
					routeRequest.setInstanceId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'LOCAL_GATEWAY':
					routeRequest.setLocalGatewayId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'NAT_GATEWAY':
					routeRequest.setNatGatewayId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'NETWORK_INTERFACE':
					routeRequest.setNetworkInterfaceId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'TRANSIT_GATEWAY':
					routeRequest.setTransitGatewayId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
				case 'VPC_PEERING_CONNECTION':
					routeRequest.setVpcPeeringConnectionId(opts.destination)
					routeRequest.setDestinationCidrBlock(opts.destinationCidrBlock)
					break
			}
			def routeResults = amazonClient.createRoute(routeRequest)
			log.info "create results: ${routeResults}"
			rtn.success = routeResults.getReturn()
			rtn.routeRequest = routeRequest
		} catch(e) {
			log.error("createRoute error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static createRouter(opts) {
		def rtn = [success:false, internetGatewayId: null]
		try {
			def name = opts.name
			def vpcId = opts.vpcId
			
			def routerRequest = new CreateInternetGatewayRequest()
			def routerResults = opts.amazonClient.createInternetGateway(routerRequest)
			log.info "create results: ${routerResults}"
			rtn.internetGatewayId = routerResults.getInternetGateway().getInternetGatewayId()
			
			def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', name)
			def resourceList = new LinkedList<String>()
			resourceList.add(rtn.internetGatewayId)
			def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			tagList.add(nameTag)
			def tagRequest = new CreateTagsRequest(resourceList, tagList)
			opts.amazonClient.createTags(tagRequest)			
			
			// Attach it to the VPC.. if vpc is specified
			try {
				if(vpcId) {
					AttachInternetGatewayRequest attachRequest = new AttachInternetGatewayRequest().withInternetGatewayId(rtn.internetGatewayId).withVpcId(vpcId)
					opts.amazonClient.attachInternetGateway(attachRequest)
				}
			} catch(e) {
				log.error "failure to attach so delete ${rtn.internetGatewayId}"
				rtn.msg = e.message
				
				def deleteRequest = new DeleteInternetGatewayRequest()
				deleteRequest.setInternetGatewayId(rtn.internetGatewayId)
				opts.amazonClient.deleteInternetGateway(deleteRequest)
				return rtn
			}
			
			rtn.success = true
			rtn.router = routerResults
		} catch(e) {
			log.error("createRouter error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static detachInternetGateway(opts) {
		log.debug "detachInternetGateway: ${opts}"
		def rtn = [success:false]
		try {
			def internetGatewayId = opts.internetGatewayId
			def vpcId = opts.vpcId
			
			def detachRequest = new DetachInternetGatewayRequest()
			detachRequest.setInternetGatewayId(internetGatewayId)
			detachRequest.setVpcId(vpcId)
			def detachResult = opts.amazonClient.detachInternetGateway(detachRequest)
			log.info "detach results: ${detachResult}"
			rtn.success = true
		} catch(e) {
			log.error("detachInternetGateway error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static attachInternetGateway(opts) {
		log.debug "attachInternetGateway: ${opts}"
		def rtn = [success:false]
		try {
			def internetGatewayId = opts.internetGatewayId
			def vpcId = opts.vpcId
			
			def attachRequest = new AttachInternetGatewayRequest()
			attachRequest.setInternetGatewayId(internetGatewayId)
			attachRequest.setVpcId(vpcId)
			def attachResult = opts.amazonClient.attachInternetGateway(attachRequest)
			log.info "attach results: ${attachResult}"
			rtn.success = true
		} catch(e) {
			log.error("attachInternetGateway error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static deleteInternetGateway(opts) {
		log.debug "deleteInternetGateway: ${opts}"
		def rtn = [success:false]
		try {
			def internetGatewayId = opts.internetGatewayId
			
			def deleteRequest = new DeleteInternetGatewayRequest()
			deleteRequest.setInternetGatewayId(internetGatewayId)
			def deleteResult = opts.amazonClient.deleteInternetGateway(deleteRequest)
			log.info "delete results: ${deleteResult}"
			rtn.success = true
		} catch(e) {
			log.error("deleteInternetGateway error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static updateInternetGateway(opts) {
		log.debug "updateInternetGateway: ${opts}"
		def rtn = [success:false]
		try {
			def internetGatewayId = opts.internetGatewayId
			def name = opts.name
			def vpcId = opts.vpcId
			def detachPoolId = opts.detachPoolId
			def attachPoolId = opts.attachPoolId
			// Update the name
			def tagOpts = [server:[externalId:internetGatewayId]] + opts
			removeEc2Tags(tagOpts)
			rtn = applyEc2Tags(tagOpts)
		} catch(e) {
			log.error("updateInternetGateway error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}
	
	static deleteRoute(opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			if((opts.destinationCidrBlock || opts.destinationIpv6CidrBlock) && opts.routeTableId) {
				def deleteRequest = new DeleteRouteRequest()
					.withDestinationCidrBlock(opts.destinationCidrBlock)
					.withDestinationIpv6CidrBlock(opts.destinationIpv6CidrBlock)
					.withRouteTableId(opts.routeTableId)
				amazonClient.deleteRoute(deleteRequest)
				rtn.success = true
			}
		} catch(e) {
			log.error("deleteRoute error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static listSubnets(opts) {
	def rtn = [success:false, subnetList:[]]
		try {
			def amazonClient = opts.amazonClient
			def vpcId = opts.zone.getConfigProperty('vpc')
			def subnetId = opts.subnetId
			def subnetRequest = new DescribeSubnetsRequest().withFilters(new LinkedList<Filter>())
			if(subnetId) {
				subnetRequest.getFilters().add(new Filter().withName("subnet-id").withValues(subnetId))
			}
			if(vpcId) {
				subnetRequest.getFilters().add(new Filter().withName("vpc-id").withValues(vpcId))
			}
			
			rtn.subnetList =  amazonClient.describeSubnets(subnetRequest).getSubnets()
			rtn.success = true
		} catch(e) {
			log.debug("listSubnets error: ${e}", e)
		}
		return rtn
	}

	static listDbSubnetGroups(opts) {
	def rtn = [success:false, subnetGroupList:[]]
		try {
			AmazonRDS amazonClient = opts.amazonClient as AmazonRDS
			def vpcId = opts.zone.getConfigProperty('vpc')
			def subnetRequest = new com.amazonaws.services.rds.model.DescribeDBSubnetGroupsRequest().withFilters(new LinkedList<Filter>())
			// According to the API, filters are not currently supported :(
			//subnetRequest.getFilters().add(new com.amazonaws.services.rds.model.Filter().withName("vpc-id").withValues(vpcId))
			rtn.subnetGroupList = amazonClient.describeDBSubnetGroups(subnetRequest).getDBSubnetGroups()

			// Post process
			if(vpcId) {
				rtn.subnetGroupList = rtn.subnetGroupList.findAll { it.getVpcId() == vpcId }
			}

			rtn.success = true
		} catch(e) {
			log.warn("listDbSubnetGroups error: ${e}", e)
		}
		return rtn
	}

	static listDBEngineVersions(opts) {
	def rtn = [success:false, dbEngineVersions:[]]
		try {
			def amazonClient = opts.amazonClient
			def request = new DescribeDBEngineVersionsRequest()
			request.setMaxRecords(100)
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.describeDBEngineVersions(request)
				results?.getDBEngineVersions()?.each {
					rtn.dbEngineVersions << it
				}
				if(results.getMarker()) {
					request.setMarker(results.getMarker())
				} else {
					hasMore = false
				}
			}
			rtn.success = true
		} catch(e) {
			log.warn("listDBEngineVersions error: ${e}", e)
		}
		return rtn
	}

	static listElasticIPs(opts) {
		def rtn = [success:false, elasticIPs:[]]
		try {
			def amazonClient = opts.amazonClient
			def domainType = opts.cloud.getConfigProperty('vpc') ? 'vpc' : 'standard'
			DescribeAddressesRequest describeAddressesRequest = new DescribeAddressesRequest().withFilters(new LinkedList<Filter>())
			describeAddressesRequest.getFilters().add(new Filter().withName("domain").withValues(domainType))
			rtn.elasticIPs = amazonClient.describeAddresses().getAddresses()
			rtn.success = true
		} catch(e) {
			log.error("listElasticIPs error: ${e}", e)
		}
		return rtn
	}


	static listCloudWatchAlarms(opts) {
		def rtn = [success:false, alarms:[]]
		try {
			AmazonCloudWatch amazonClient = opts.amazonClient
			boolean done = false
			DescribeAlarmsRequest request = new DescribeAlarmsRequest()

			while(!done) {

				DescribeAlarmsResult response = amazonClient.describeAlarms(request)
				rtn.alarms += response.getMetricAlarms()
				
				request.setNextToken(response.getNextToken())
				if(response.getNextToken() == null) {
					done = true
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("listElasticIPs error: ${e}", e)
		}
		return rtn
	}

	static listAccountAttributes(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			DescribeAccountAttributesRequest request = new DescribeAccountAttributesRequest()
			rtn.results = amazonClient.describeAccountAttributes(request)
			rtn.success = true
		} catch(e) {
			log.debug("listAccountAttributes error: ${e}", e)
		}
		return rtn
	}

	static listInstanceProfiles(opts) {
		def rtn = [success:false, results:[]]
		try {
			def amazonClient = opts.amazonClient
			def request = new ListInstanceProfilesRequest()
			request.setMaxItems(100)
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.listInstanceProfiles(request)
				results?.getInstanceProfiles()?.each {
					rtn.results << it
				}
				if(results.isTruncated()) {
					request.setMarker(results.getMarker())
				} else {
					hasMore = false
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("listInstanceProfiles error: ${e.message}")
		}
		return rtn
	}

	static listRoles(opts) {
		def rtn = [success:false, results:[]]
		try {
			AmazonIdentityManagement amazonClient = opts.amazonClient as AmazonIdentityManagement
			ListRolesRequest request = new ListRolesRequest()
			request.setMaxItems(100)
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.listRoles(request)
				results?.getRoles()?.each {
					rtn.results << it
				}
				if(results.isTruncated()) {
					request.setMarker(results.getMarker())
				} else {
					hasMore = false
				}
			}
			rtn.success = true
		} catch(AmazonEC2Exception ec2e) {
			if(ec2e.getErrorCode() == 'UnauthorizedOperation') {
				log.debug("listTransitGatewayVpcAttachments error: ${ec2e}")
			} else {
				log.error("listTransitGatewayVpcAttachments error: ${ec2e}")
			}	
		} catch(e) {
			log.error("listRoles error: ${e.message}")
		}
		return rtn
	}

	static listVpnEndpoints(Map authConfig, Map opts) {
		def rtn = [success:false, vpnEndpoints:[]]
		try {
			def amazonClient = authConfig.amazonClient
			def page = opts.page ?: 0
			def perPage = opts.perPage ?: 100
			def keepGoing = true
			//build request
			def apiRequest = new DescribeClientVpnEndpointsRequest().withMaxResults(perPage)
			//load data
			while(keepGoing) {
				def apiResults = amazonClient.describeClientVpnEndpoints(apiRequest)
				def apiList = apiResults.getClientVpnEndpoints()
				if(apiList.size() > 0)
					rtn.vpnEndpoints += apiList
				//check for more
				def nextPageToken = apiResults.getNextToken()
				if(nextPageToken) {
					apiRequest = new DescribeClientVpnEndpointsRequest().withMaxRecords(perPage).withNextToken(nextPageToken)
				} else {
					keepGoing = false
					rtn.success = true
				}
			}
		} catch(e) {
			rtn.msg = e.message
			log.error("listVpnEndpoints error: ${e}")
		}
		return rtn
	}

	static listVpnConnections(Map authConfig, Map opts) {
		def rtn = [success:false, vpnConnections:[]]
		try {
			def amazonClient = authConfig.amazonClient
			def page = opts.page ?: 0
			def perPage = opts.perPage ?: 100
			def keepGoing = true
			//build request
			def apiRequest = new DescribeClientVpnConnectionsRequest().withMaxResults(perPage)
			//load data
			while(keepGoing) {
				def apiResults = amazonClient.describeClientVpnConnections(apiRequest)
				def apiList = apiResults.getConnections()
				if(apiList.size() > 0)
					rtn.vpnConnections += apiList
				//check for more
				def nextPageToken = apiResults.getNextToken()
				if(nextPageToken) {
					apiRequest = new DescribeClientVpnConnectionsRequest().withMaxRecords(perPage).withNextToken(nextPageToken)
				} else {
					keepGoing = false
					rtn.success = true
				}
			}
		} catch(e) {
			rtn.msg = e.message
			log.error("listVpnConnections error: ${e}")
		}
		return rtn
	}

	static listVpnGateways(Map authConfig, Map opts) {
		def rtn = [success:false, vpnGateways:[]]
		try {
			def amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeVpnGatewaysRequest()
			//load data
			def apiResults = amazonClient.describeVpnGateways(apiRequest)
			def apiList = apiResults.getVpnGateways()
			rtn.vpnGateways += apiList
			rtn.success = true
		} catch(AmazonEC2Exception ec2e) {
			if(ec2e.getErrorCode() == 'UnauthorizedOperation') {
				log.debug("listVpnGateways error: ${ec2e}")
			} else {
				log.error("listVpnGateways error: ${ec2e}")
			}
		} catch(e) {
			rtn.msg = e.message
			log.error("listVpnGateways error: ${e}")
		}
		return rtn
	}
	
	static listInternetGateways(Map authConfig, Map opts = [:]) {
		def rtn = [success:false, internetGateways:[]]
		try {
			AmazonEC2 amazonClient = authConfig.amazonClient as AmazonEC2
			//build request
			def apiRequest = new DescribeInternetGatewaysRequest()
			if(opts.internetGatewayId) {
				def gatewayIds = new LinkedList<String>()
				gatewayIds.add(new String(opts.internetGatewayId))
				apiRequest.setInternetGatewayIds(gatewayIds)
			}
			//load data
			def apiResults = amazonClient.describeInternetGateways(apiRequest)
			def apiList = apiResults.getInternetGateways()
			rtn.internetGateways += apiList
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listInternetGateways error: ${e}")
		}
		return rtn
	}
	
	static listEgressOnlyInternetGateways(Map authConfig, Map opts) {
		def rtn = [success:false, egressOnlyInternetGateways:[]]
		try {
			AmazonEC2 amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeEgressOnlyInternetGatewaysRequest()
			//load data
			def apiResults = amazonClient.describeEgressOnlyInternetGateways(apiRequest)
			def apiList = apiResults.getEgressOnlyInternetGateways()
			rtn.egressOnlyInternetGateways += apiList
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listEgressOnlyInternetGateways error: ${e}")
		}
		return rtn
	}
	
	static listNatGateways(Map authConfig, Map opts) {
		def rtn = [success:false, natGateways:[]]
		try {
			AmazonEC2 amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeNatGatewaysRequest()
			//load data
			def apiResults = amazonClient.describeNatGateways(apiRequest)
			def apiList = apiResults.getNatGateways()
			rtn.natGateways += apiList
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listNatGateways error: ${e}")
		}
		return rtn
	}
	
	static listNetworkInterfaces(Map authConfig, Map opts) {
		def rtn = [success:false, networkInterfaces:[]]
		try {
			AmazonEC2 amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeNetworkInterfacesRequest()
			//load data
			def apiResults = amazonClient.describeNetworkInterfaces(apiRequest)
			def apiList = apiResults.getNetworkInterfaces()
			rtn.networkInterfaces += apiList
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listNetworkInterfaces error: ${e}")
		}
		return rtn
	}
	
	static listVpcPeeringConnections(Map authConfig, Map opts) {
		def rtn = [success:false, vpcPeeringConnections:[]]
		try {
			def amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeVpcPeeringConnectionsRequest()
			//load data
			def apiResults = amazonClient.describeVpcPeeringConnections(apiRequest)
			def apiList = apiResults.getVpcPeeringConnections()
			rtn.vpcPeeringConnections += apiList
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listNetworkInterfaces error: ${e}")
		}
		return rtn
	}
	
	static listTransitGateways(Map authConfig, Map opts) {
		def rtn = [success:false, transitGateways:[]]
		try {
			AmazonEC2 amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeTransitGatewaysRequest()
			//load data
			def apiResults = amazonClient.describeTransitGateways(apiRequest)
			def apiList = apiResults.getTransitGateways()
			rtn.transitGateways += apiList
			rtn.success = true
		} catch(AmazonEC2Exception ec2e) {
			if(ec2e.getErrorCode() == 'UnauthorizedOperation') {
				log.debug("listTransitGateways error: ${ec2e}")
			} else {
				log.error("listTransitGateways error: ${ec2e}")
			}	
		} catch(e) {
			rtn.msg = e.message
			log.error("listTransitGateways error: ${e}")
		}
		return rtn
	}
	
	static listTransitGatewayVpcAttachments(Map authConfig, Map opts) {
		def rtn = [success:false, transitGatewayVpcAttachments:[]]
		try {
			def amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new DescribeTransitGatewayVpcAttachmentsRequest()
			//load data
			def apiResults = amazonClient.describeTransitGatewayVpcAttachments(apiRequest)
			def apiList = apiResults.getTransitGatewayVpcAttachments()
			rtn.transitGatewayVpcAttachments += apiList
			rtn.success = true
		} catch(AmazonEC2Exception ec2e) {
			if(ec2e.getErrorCode() == 'UnauthorizedOperation') {
				log.debug("listTransitGatewayVpcAttachments error: ${ec2e}")
			} else {
				log.error("listTransitGatewayVpcAttachments error: ${ec2e}")
			}	
		} catch(e) {
			rtn.msg = e.message
			log.error("listTransitGatewayVpcAttachments error: ${e}")
		}
		return rtn
	}
	
	

	static listElasticsearchDomains(Map authConfig, Map opts) {
		def rtn = [success:false, elasticDomains:[]]
		try {
			def amazonClient = authConfig.amazonClient
			//build request
			def apiRequest = new ListDomainNamesRequest()
			def apiResults = amazonClient.listDomainNames(apiRequest)
			def apiList = apiResults.getDomainNames()?.collect{ it.getDomainName() }
			//need to page this - its 5 max per call...
			def keepGoing = true
			while(keepGoing) {
				if(apiList.size() > 0) {
					def loadList = apiList.take(5)
					//load status info
					apiRequest = new DescribeElasticsearchDomainsRequest().withDomainNames(loadList)
					//load data
					apiResults = amazonClient.describeElasticsearchDomains(apiRequest)
					def callList = apiResults.getDomainStatusList()
					if(callList?.size() > 0)
						rtn.elasticDomains += callList
					else
						keepGoing = false
				} else {
					keepGoing = false
				}
			}
			rtn.success = true
			//done
		} catch(e) {
			rtn.msg = e.message
			log.error("listElasticsearchDomains error: ${e}")
		}
		return rtn
	}

	static getFreeEIP(opts) {
		log.debug "createEIP: ${opts}"
		def rtn = [success:false]
		try {
			// listElasticIPs(opts)?.collect { eip ->
			// 	println "eip record: ${eip.publicIp} - ${eip.assocationId}"
			// }
			def freeIp = listElasticIPs(opts)?.elasticIPs?.find{it.associationId == null}
			if(freeIp) {
				rtn.success = true
				rtn.allocationId = freeIp.allocationId
				rtn.publicIp = freeIp.publicIp
			}

		} catch(e) {
			log.error("getFreeIP error: ${e}", e)
		}
		return rtn
	}

	static createEIP(opts) {
		log.debug "createEIP: ${opts}"
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			AllocateAddressRequest addressRequest = new AllocateAddressRequest()
			addressRequest.domain = opts.zone.getConfigProperty('vpc') ? 'vpc' : 'standard'
			AllocateAddressResult allocateResult = amazonClient.allocateAddress(addressRequest)
			rtn.result = allocateResult
			rtn.success = true
		} catch(e) {
			log.error("createEIP error: ${e}", e)
		}
		return rtn
	}

	static associateEIP(opts) {
		log.debug "associateEIP: ${opts}"
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			AssociateAddressRequest associateAddressRequest = new AssociateAddressRequest()
			associateAddressRequest.allocationId = opts.allocationId
			associateAddressRequest.instanceId = opts.externalId
			if (opts.interfaceId)
				associateAddressRequest.networkInterfaceId = opts.interfaceId
			AssociateAddressResult response = amazonClient.associateAddress(associateAddressRequest)
			rtn.result = response
			rtn.success = true
		} catch(e) {
			log.debug("associateEIP error: ${e}", e)
		}
		return rtn
	}

	static releaseEIP(opts) {
		log.debug "releaseEIP: ${opts}"
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient

			try {
				DisassociateAddressRequest disassociateRequest = new DisassociateAddressRequest()
				if(opts.eipPublicIp && !isVPC(opts.zone)) {
					disassociateRequest.publicIp = opts.eipPublicIp
				}
				if(opts.eipAssociationId && isVPC(opts.zone)) {
					disassociateRequest.associationId = opts.eipAssociationId
				}
				amazonClient.disassociateAddress(disassociateRequest)
			} catch(e)  {
				log.info "Disassociate request failed: ${e}", e
			}

			ReleaseAddressRequest releaseRequest = new ReleaseAddressRequest()
			if(opts.eipPublicIp && !isVPC(opts.zone)) {
				releaseRequest.publicIp = opts.eipPublicIp
			}
			if(opts.eipAllocationId && isVPC(opts.zone)) {
				releaseRequest.allocationId = opts.eipAllocationId
			}
			amazonClient.releaseAddress(releaseRequest)

			rtn.success = true
		} catch(e) {
			log.debug("releaseEIP error: ${e}", e)
		}
		return rtn
	}

	static checkServerReady(opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 10l)
				def serverDetail = getServerDetail(opts)
				if(serverDetail.success == true && serverDetail?.server?.getState()) {
					def tmpState = serverDetail.server.getState().getCode()
					if(tmpState == 16) {
						rtn.success = true
						rtn.results = serverDetail.server
						rtn.volumes = serverDetail.volumes
						pending = false
					} else if(tmpState > 16) {
						rtn.error = true
						rtn.results = serverDetail.server
						rtn.volumes = serverDetail.volumes
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 15)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static waitForServerStatus(opts, stateCode) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 5l)
				def serverDetail = getServerDetail(opts)
				if(serverDetail.success == true && serverDetail?.server?.getState()) {
					def tmpState = serverDetail.server.getState().getCode()
					if(tmpState == stateCode) {
						rtn.success = true
						rtn.results = serverDetail.server
						rtn.volumes = serverDetail.volumes
						pending = false
					}
				}
				attempts ++
				if(attempts > 100)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static waitForServerExists(opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				if(opts.forceFetchAmazonClient?.enabled) {
					def server = opts.forceFetchAmazonClient.server
					def morpheusContext = opts.forceFetchAmazonClient.morpheusContext
					def tmpServer = morpheusContext.async.computeServer.get(server.id).blockingGet()
					opts.amazonClient = getAmazonClient(server.cloud, true, tmpServer.resourcePool?.regionCode ?: tmpServer.region?.regionCode)
				}
				def serverDetail = getServerDetail(opts)
				if(serverDetail.success == true && serverDetail?.server?.getState()) {
					rtn.success = true
					rtn.results = serverDetail.server
					break						
				}
				attempts ++
				if(attempts > 100) {
					pending = false
				} else {
					sleep(1000l * 5l)
				}
			}
		} catch(e) {
			log.error "Error in waitForServerExists: ${e}", e
		}
		return rtn
	}

	static checkPasswordReady(opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 5l)
				def serverPassword = getServerPassword(opts)
				if(serverPassword.success == true && serverPassword?.password?.length() > 0) {
					rtn.success = true
					rtn.password = decryptAmazonPassword(opts.primaryKey?.privateKey, serverPassword.password)
					pending = false
				}
				attempts ++
				if(attempts > 300)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkRdsServerReady(opts) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 10l)
				def serverDetail = getRdsServerDetail(opts)
				if(serverDetail.success == true && serverDetail?.server?.getDBInstanceStatus()) {
					def tmpState = serverDetail.server.getDBInstanceStatus()
					log.debug("rds status: ${tmpState}")
					if(tmpState == 'available') {
						rtn.success = true
						rtn.results = serverDetail.server
						pending = false
					} else if(tmpState == 'failed') {
						rtn.error = true
						rtn.results = serverDetail.server
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 120)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static checkImportImageReady(opts, taskId) {
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			def lastProgress
			while(pending) {
				sleep(1000l * 30l)
				def taskDetail = getImportTaskDetail(opts, taskId)
				if(taskDetail.success == true && taskDetail?.status) {
					if(taskDetail.status == 'completed') {
						rtn.success = true
						rtn.results = taskDetail
						rtn.imageId = taskDetail.imageId
						pending = false
					} else if(taskDetail.status == 'deleting' || taskDetail.status == 'deleted' || taskDetail.status == 'error' || taskDetail.status == 'failed') {
						rtn.error = true
						rtn.results = taskDetail
						rtn.success = true
						pending = false
					} else {
						if(lastProgress && taskDetail.progress != lastProgress) {
							attempts = 0 //reset attempts since we are getting movement
						}
						lastProgress = taskDetail.progress
					}
				}
				attempts ++
				if(attempts > 1000)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static getServerStatus(opts) {
		def rtn = [success:false]
		try {
			log.debug "getServerStatus opts: ${opts}"
			def amazonClient = opts.amazonClient

			def serverRequest = new DescribeInstanceStatusRequest().withInstanceIds(new LinkedList<String>())
			serverRequest.getInstanceIds().add(new String(opts.server.externalId))
			def tmpList = amazonClient.describeInstanceStatus(serverRequest).getInstanceStatuses()
			rtn.server = tmpList?.size() > 0 ? tmpList.get(0) : null
			rtn.success = rtn.server != null
		} catch(e) {
			log.error("getServerStatus error: ${e}", e)
		}
		return rtn
	}

	static getServerPassword(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new GetPasswordDataRequest(opts.server.externalId)
			def serverResult = amazonClient.getPasswordData(serverRequest)
			rtn.password = serverResult.getPasswordData()
			rtn.success = rtn.password != null
		} catch(e) {
			log.error("getServerPassword error: ${e}", e)
		}
		return rtn
	}

	static getServerDetail(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new DescribeInstancesRequest().withInstanceIds(new LinkedList<String>())
			def externalId = opts.server?.externalId ?: opts.serverId
			if(externalId) {
				serverRequest.getInstanceIds().add(new String(externalId))
				def tmpReservations = amazonClient.describeInstances(serverRequest).getReservations()
				rtn.reservation = tmpReservations?.size() > 0 ? tmpReservations.get(0) : null
				def tmpInstances = rtn.reservation?.getInstances()
				rtn.server = tmpInstances?.size() > 0 ? tmpInstances.get(0) : null
				if(rtn.server) {
					rtn.volumes = getVmVolumes(rtn.server)
					rtn.networks = getVmNetworks(rtn.server)
				}
				rtn.success = rtn.server != null	
			}
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				//not found - no big deal
				log.warn("getServerDetail - instance not found: ${awsError}")
			} else {
				log.error("getServerDetail error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("getServerDetail error: ${e}", e)
		}
		return rtn
	}

	static listRdsInstances(Map opts) {
		def rtn = [success:false, dbInstances:[]]
		try {
			def amazonClient = opts.amazonClient
			def request = new DescribeDBInstancesRequest()
			request.setMaxRecords(100)
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.describeDBInstances(request)
				results?.getDBInstances()?.each {
					rtn.dbInstances << it
				}
				if(results.getMarker()) {
					request.setMarker(results.getMarker())
				} else {
					hasMore = false
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("listRdsInstances error: ${e}", e)
		}
		return rtn
	}

	static getRdsServerStatus(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new DescribeDBInstancesRequest().withDBInstanceIdentifier(opts.server.externalId)
			def tmpList = amazonClient.describeDBInstances(serverRequest).getDBInstances()
			rtn.server = tmpList?.size() > 0 ? tmpList.get(0) : null
			rtn.success = rtn.server != null
		} catch(e) {
			log.error("getRdsServerStatus error: ${e}", e)
		}
		return rtn
	}

	static getRdsServerDetail(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new DescribeDBInstancesRequest().withDBInstanceIdentifier(opts.server.externalId)
			def tmpList = amazonClient.describeDBInstances(serverRequest).getDBInstances()
			rtn.server = tmpList?.size() > 0 ? tmpList.get(0) : null
			rtn.success = rtn.server != null
		} catch(com.amazonaws.services.rds.model.DBInstanceNotFoundException e) {
			log.warn("db instance not found: ${opts?.server?.externalId}")
			log.debug("getServerDetail db not found: ${e}", e)
		} catch(e) {
			log.error("getRdsServerDetail error: ${e}", e)
		}
		return rtn
	}


	static listVolumes(opts) {
		def rtn = [success:false, volumeList: []]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def vpcId = opts.zone.getConfigProperty('vpc')
			def volumesRequest = new DescribeVolumesRequest().withFilters(new LinkedList<Filter>())
			
			
			volumesRequest.setMaxResults(1000)
			def volResponse = amazonClient.describeVolumes(volumesRequest)
			def tmpVolumes = volResponse.getVolumes()
			
			
			
			// Fetch all the instance information first.. while collecting the volumeIds
			
			while(tmpVolumes.size() > 0) {
				tmpVolumes.each { tmpVolume ->
					rtn.volumeList << tmpVolume
				}
				def nextPageToken = volResponse.getNextToken()
				if(!nextPageToken) {
					break
				}
				volumesRequest = new DescribeVolumesRequest().withNextToken(nextPageToken)
				
				volumesRequest.setMaxResults(1000)
				volResponse = amazonClient.describeVolumes(volumesRequest)
				tmpVolumes = volResponse.getVolumes()
			}
			rtn.success = true
		} catch(e) {
			log.error("listVolumes error: ${e}", e)
		}
		return rtn
	}

	static listSnapshots(opts) {
		def rtn = [success:false, snapshotList:[]]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def snapshotsRequest = new DescribeSnapshotsRequest().withFilters(new LinkedList<Filter>())
			snapshotsRequest.setMaxResults(1000)
			snapshotsRequest.withOwnerIds("self")
			DescribeSnapshotsResult snapResponse = amazonClient.describeSnapshots(snapshotsRequest)
			def tmpSnapshots = snapResponse.getSnapshots()

			// Fetch all the instance information first.. while collecting the volumeIds
			while(tmpSnapshots.size() > 0) {
				rtn.snapshotList += tmpSnapshots

				def nextPageToken = snapResponse.getNextToken()
				if(!nextPageToken) {
					break
				}
				snapshotsRequest = new DescribeSnapshotsRequest().withNextToken(nextPageToken)

				snapshotsRequest.setMaxResults(1000)
				snapResponse = amazonClient.describeSnapshots(snapshotsRequest)
				tmpSnapshots = snapResponse.getSnapshots()
			}
			rtn.success = true
		} catch(e) {
			log.error("listSnapshots error: ${e}", e)
		}
		return rtn
	}

	static listVpcServers(opts) {
		def rtn = [success:false, serverList:[], volumeList: [:], snapshotList: [:]]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def vpcId = opts.cloud.getConfigProperty('vpc')
			def serverRequest = new DescribeInstancesRequest().withFilters(new LinkedList<Filter>())
			if(!opts.includeAllVPCs && vpcId) {
				serverRequest.getFilters().add(new Filter().withName("vpc-id").withValues(vpcId))
			}
			if(opts.filterInstanceId) {
				serverRequest.getFilters().add(new Filter().withName("instance-id").withValues(opts.filterInstanceId))
			}
			serverRequest.setMaxResults(1000)
			def response = amazonClient.describeInstances(serverRequest)
			def tmpReservations = response.getReservations()
			
			
			// Fetch all the instance information first.. while collecting the volumeIds
			def volumeIds = []
			while(tmpReservations.size() > 0) {
				tmpReservations.each { reservation ->
					def instances = reservation.getInstances()
					instances.each { resInstance ->
						resInstance.getBlockDeviceMappings()?.each { block ->
							volumeIds << block.getEbs().getVolumeId()
						}
						rtn.serverList << resInstance
					}
				}
				def nextPageToken = response.getNextToken()
				if(!nextPageToken) {
					break
				}
				serverRequest = new DescribeInstancesRequest().withNextToken(nextPageToken)
				if(!opts.includeAllVPCs && vpcId) {
					serverRequest.getFilters().add(new Filter().withName("vpc-id").withValues(vpcId))
				}
				serverRequest.setMaxResults(1000)
				response = amazonClient.describeInstances(serverRequest)
				tmpReservations = response.getReservations()
			}
			// Now.. fetch all the volume information with max size of 200.. and the snapshots
			if(volumeIds) {
				def volumePageSize = 200
				def idx = 0
				while( idx < volumeIds.size()) {
					def lastIndex = Math.min(idx + volumePageSize - 1, volumeIds.size() - 1)
					def volumeIdsToFetch = volumeIds[idx..lastIndex]
					def volumesRequest = new DescribeVolumesRequest().withFilters(new LinkedList<Filter>())
					volumesRequest.getFilters().add(new Filter().withName('volume-id').withValues(volumeIdsToFetch))
					def volResponse = amazonClient.describeVolumes(volumesRequest)
					def tmpVolumes = volResponse.getVolumes()
					while (tmpVolumes.size() > 0) {
						tmpVolumes.each { Volume volume ->
							rtn.volumeList[volume.getVolumeId()] = volume
						}
						def nextPageVolToken = volResponse.getNextToken()
						if (!nextPageVolToken) {
							break
						}
						volumesRequest = new DescribeVolumesRequest().withNextToken(nextPageVolToken)
						volResponse = amazonClient.describeVolumes(volumesRequest)
						tmpVolumes = volResponse.getVolumes()
					}
					idx = lastIndex + 1
				}

				// Fetch the snapshots in pages and only return those that are associated with volumes we care about (volumeIds)
				// First.. page the volumeIds.. do 100 at a time
				volumePageSize = 100
				idx = 0
				while( idx < volumeIds.size()) {
					def lastIndex = Math.min(idx + volumePageSize - 1, volumeIds.size() - 1)
					def volumeIdsToFetch = volumeIds[idx..lastIndex]

					def snapshotRequest = new DescribeSnapshotsRequest().withFilters(new LinkedList<Filter>())
					snapshotRequest.getFilters().add(new Filter().withName('progress').withValues('100%'))
					snapshotRequest.getFilters().add(new Filter().withName('status').withValues('completed'))
					snapshotRequest.getFilters().add(new Filter().withName('volume-id').withValues(volumeIdsToFetch))
					snapshotRequest.setMaxResults(500)
					response = amazonClient.describeSnapshots(snapshotRequest)
					def tmpSnapshots = response.getSnapshots()

					while (tmpSnapshots.size() > 0 || response.getNextToken()) {
						tmpSnapshots.each { snapshot ->
							rtn.snapshotList[snapshot.volumeId] = rtn.snapshotList[snapshot.volumeId] ?: []
							rtn.snapshotList[snapshot.volumeId] << snapshot
						}
						def nextPageToken = response.getNextToken()
						if (!nextPageToken) {
							break
						}
						snapshotRequest = new DescribeSnapshotsRequest().withNextToken(nextPageToken)
						snapshotRequest.getFilters().add(new Filter().withName('progress').withValues('100%'))
						snapshotRequest.getFilters().add(new Filter().withName('status').withValues('completed'))
						snapshotRequest.getFilters().add(new Filter().withName('volume-id').withValues(volumeIdsToFetch))
						snapshotRequest.setMaxResults(500)
						response = amazonClient.describeSnapshots(snapshotRequest)
						tmpSnapshots = response.getSnapshots()
					}
					idx = lastIndex + 1
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("listVpcServers error: ${e}", e)
		}
		return rtn
	}

	static stopServer(opts) {
		def rtn = [success:false]
		try {
			def statusResult = getServerStatus(opts)
			log.debug "stopServer statusResult: ${statusResult}"
			if(!statusResult.success && statusResult.server == null) {
				log.warn "This server probably doesn't exist anymore in Amazon. ${opts.server.externalId}"
			}
			def amazonClient = opts.amazonClient
			def serverRequest = new StopInstancesRequest().withInstanceIds(new LinkedList<String>())
			serverRequest.getInstanceIds().add(new String(opts.server.externalId))
			def tmpResults = amazonClient.stopInstances(serverRequest)
			def tmpInstances = tmpResults.getStoppingInstances()
			rtn.instanceList = tmpInstances?.collect{ return [instanceId:it.getInstanceId(), instanceState:it.getCurrentState()]}
			rtn.success = rtn.instanceList?.size() > 0
		} catch(e) {
			log.error("stopServer error: ${e}", e)
		}
		return rtn
	}

	static startServer(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new StartInstancesRequest().withInstanceIds(new LinkedList<String>())
			serverRequest.getInstanceIds().add(new String(opts.server.externalId))
			def tmpResults = amazonClient.startInstances(serverRequest)
			def tmpInstances = tmpResults.getStartingInstances()
			rtn.instanceList = tmpInstances?.collect{return [instanceId:it.getInstanceId(), instanceState:it.getCurrentState()]}
			rtn.success = rtn.instanceList?.size() > 0
		} catch(e) {
			log.error("startServer error: ${e}", e)
		}
		return rtn
	}

	static rebootServer(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new RebootInstancesRequest().withInstanceIds(new LinkedList<String>())
			serverRequest.getInstanceIds().add(new String(opts.server.externalId))
			def tmpResults = amazonClient.rebootInstances(serverRequest)
			
			rtn.success = true
		} catch(e) {
			log.error("startServer error: ${e}", e)
		}
		return rtn
	}


	static stopRdsServer(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new StopDBInstanceRequest().withDBInstanceIdentifier(opts.server.externalId)
			def dbInstance = amazonClient.stopDBInstance(serverRequest)
			rtn.server = dbInstance
			rtn.success = rtn.server != null
		} catch(e) {
			log.error("stopRdsServer error: ${e}", e)
		}
		return rtn
	}

	static startRdsServer(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverRequest = new StartDBInstanceRequest().withDBInstanceIdentifier(opts.server.externalId)
			def dbInstance = amazonClient.startDBInstance(serverRequest)
			rtn.server = dbInstance
			rtn.success = rtn.server != null
		} catch(e) {
			log.error("startRdsServer error: ${e}", e)
		}
		return rtn
	}

	static deleteServer(opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def serverId = opts.server.externalId
			def results = [success:true]
			if(serverId) {
				def serverRequest = new TerminateInstancesRequest().withInstanceIds(new LinkedList<String>())
				serverRequest.getInstanceIds().add(new String(opts.server.externalId))
				def tmpResults = amazonClient.terminateInstances(serverRequest)
				def tmpInstances = tmpResults.getTerminatingInstances()
				rtn.instanceList = tmpInstances?.collect{return [instanceId:it.getInstanceId(), instanceState:it.getCurrentState()]}
				results.success = rtn.instanceList?.size() > 0
			}
			if(results.success == true) {
				def volumeId = opts.server.rootVolumeId
				if(volumeId) {
					sleep(180000)
					opts.volumeId = volumeId
					results = deleteVolume(opts)
				}
			}
			rtn.success = results.success
		} catch(AmazonEC2Exception e) {
			if(e.getErrorCode() == 'InvalidInstanceID.NotFound')
				rtn.success = true
			else
				log.error("error deleting server: ${e}", e)
		} catch(Throwable e) {
			log.error("deleteServer error: ${e}", e)
		}
		return rtn
	}

	static createSubnet(Map opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			Map config = opts.config ?: [:]
			def networkRequest = new CreateSubnetRequest().withVpcId(config.vpcId)
			if(config.cidr) {
				networkRequest.withCidrBlock(config.cidr)
			}
			if(config.availabilityZone) {
				networkRequest.withAvailabilityZone(config.availabilityZone)
			}
			def networkResults = amazonClient.createSubnet(networkRequest)
			if(networkResults.getSubnet()) {
				if(config.assignPublicIp) {
					ModifySubnetAttributeRequest subnetMapRequest = new ModifySubnetAttributeRequest().withSubnetId(networkResults.getSubnet().getSubnetId())
					subnetMapRequest.withMapPublicIpOnLaunch(true)
					amazonClient.modifySubnetAttribute(subnetMapRequest)
				}
				rtn.subnet = networkResults.getSubnet()
				rtn.externalId = networkResults.getSubnet().getSubnetId()

				// //tag it
				def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', config.name)
				def resourceList = new LinkedList<String>()
				resourceList.add(networkResults.getSubnet().getSubnetId())
				def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
				tagList.add(nameTag)
				buildAdditionalTags(opts.tagList, tagList)
				def tagRequest = new CreateTagsRequest(resourceList, tagList)
				amazonClient.createTags(tagRequest)
				rtn.success = true
			}
		} catch(e) {
			log.error("createSubnet error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static createVpc(Map opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def vpcRequest = new CreateVpcRequest()
			if(opts.cidr) {
				vpcRequest.withCidrBlock(opts.cidr)
			}
			if(opts.tenancy) {
				vpcRequest.withInstanceTenancy(opts.tenancy)
			}
			def vpcResults = amazonClient.createVpc(vpcRequest)
			if(vpcResults.getVpc()) {
				rtn.vpc = vpcResults.getVpc()
				rtn.externalId = vpcResults.getVpc().getVpcId()
				// //tag it
				def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', opts.name)
				def resourceList = new LinkedList<String>()
				resourceList.add(vpcResults.getVpc().getVpcId())
				def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
				tagList.add(nameTag)
				buildAdditionalTags(opts.tagList, tagList)
				def tagRequest = new CreateTagsRequest(resourceList, tagList)
				amazonClient.createTags(tagRequest)
				rtn.success = true
			}
		} catch(e) {
			log.error("createSubnet error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static createSecurityGroup(Map opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def securityGroupConfig = opts.config
			log.debug("AWS create security group config: ${opts.config}")
			CreateSecurityGroupRequest request = new CreateSecurityGroupRequest()
			request.withGroupName(securityGroupConfig.name)
			request.withDescription(securityGroupConfig.description)
			request.withVpcId(securityGroupConfig.vpcId)
			CreateSecurityGroupResult result = amazonClient.createSecurityGroup(request)
			log.debug("aws security group result: ${result}")
			if(result.groupId) {
				rtn.data = [
					externalId: result.groupId
				]
				rtn.success = true
			}
		} catch(e) {
			log.error("createSecurityGroup error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static deleteSecurityGroup(Map opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			DeleteSecurityGroupRequest request = new DeleteSecurityGroupRequest().withGroupId(opts.groupId)
			DeleteSecurityGroupResult result = amazonClient.deleteSecurityGroup(request)
			log.debug("aws security group result: ${result}")
			rtn.success = true
		} catch(e) {
			log.error("deleteSecurityGroup error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}


	/**
	 * Create a security group rule. Expected <b>opts</b> example:
	 * <pre>{@code
	 * 	[
	 *  amazonClient: getAmazonClient(cloud, false, "us-west1")
	 *  config: [
	 *    description: "my security group",
	 *    ipProtocol: "tcp"
	 *    minPort: 0
	 *    maxPort 80
	 *    ipRange: [
	 *      '0.0.0.0',
	 *      '1.1.1.1'
	 *    ]
	 *    targetGroupId: 7
	 *    direction: 'ingress'
	 *    securityGroupId: 8
	 *  ]
	 *]
	 * }</pre>
	 * @param opts
	 * @return a map of results
	 */
	static createSecurityGroupRule(Map opts) {
		log.debug("createSecurityGroupRule: {}", opts)
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def ruleConfig = opts.config
			def ipPermission = new IpPermission()
			ipPermission.withIpProtocol(ruleConfig.ipProtocol)
			ipPermission.withFromPort(ruleConfig.minPort)
			ipPermission.withToPort(ruleConfig.maxPort)

			if(ruleConfig.ipRange) {
				ipPermission.withIpv4Ranges(new IpRange().withCidrIp(ruleConfig.ipRange[0]).withDescription(ruleConfig.description))
			}

			if(ruleConfig.targetGroupId) {
				ipPermission.withUserIdGroupPairs(new UserIdGroupPair().withGroupId(ruleConfig.targetGroupId).withDescription(ruleConfig.description))
			}

			TagSpecification tagSpecification = null
			if(ruleConfig.name) {
				Tag nameTag = new Tag("Name", (String)ruleConfig.name)
				tagSpecification = new TagSpecification()
				tagSpecification.withResourceType(ResourceType.SecurityGroupRule)
				tagSpecification.withTags(nameTag)
			}

			def response
			if(ruleConfig.direction == 'egress') {
				AuthorizeSecurityGroupEgressRequest request = new AuthorizeSecurityGroupEgressRequest()
				request.withIpPermissions(ipPermission)
				request.withGroupId(ruleConfig.securityGroupId)
				if(tagSpecification != null) {
					request.withTagSpecifications(tagSpecification)
				}
				response = amazonClient.authorizeSecurityGroupEgress(request)
			} else {
				AuthorizeSecurityGroupIngressRequest request = new AuthorizeSecurityGroupIngressRequest()
				request.withIpPermissions(ipPermission)
				request.withGroupId(ruleConfig.securityGroupId)
				if(tagSpecification != null) {
					request.withTagSpecifications(tagSpecification)
				}
				response = amazonClient.authorizeSecurityGroupIngress(request)
			}

			if(response.isReturn() == true && response.securityGroupRules.size() > 0) {
				rtn.rule = response.securityGroupRules?.getAt(0)
				rtn.success = true
			}
		} catch(e) {
			log.error("createSecurityGroupRule error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	/**
	 * Delete a security group rule. Expected <b>opts</b> example:
	 * <pre>{@code
	 * 	[
	 *  amazonClient: getAmazonClient(cloud, false, "us-west1")
	 *  config: [
	 *    description: "my security group",
	 *    ipProtocol: "tcp"
	 *    minPort: 0
	 *    maxPort 80
	 *    ipRange: [
	 *      '0.0.0.0',
	 *      '1.1.1.1'
	 *    ]
	 *    targetGroupId: 7
	 *    direction: 'ingress'
	 *    securityGroupId: 8
	 *  ]
	 *]
	 * }</pre>
	 * @param opts
	 * @return a map of results
	 */
	static deleteSecurityGroupRule(Map opts) {
		log.debug("deleteSecurityGroupRule: {}", opts)
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def ruleConfig = opts.config
			def ipPermission = new IpPermission()
			ipPermission.withIpProtocol(ruleConfig.ipProtocol)
			ipPermission.withFromPort(ruleConfig.minPort)
			ipPermission.withToPort(ruleConfig.maxPort)
			if(ruleConfig.ipRange) {
				ipPermission.withIpv4Ranges(new IpRange().withCidrIp(ruleConfig.ipRange[0]).withDescription(ruleConfig.description))
			}
			if(ruleConfig.targetGroupId) {
				ipPermission.withUserIdGroupPairs(new UserIdGroupPair().withGroupId(ruleConfig.targetGroupId).withDescription(ruleConfig.description))
			}

			def response
			if(ruleConfig.direction == 'egress') {
				RevokeSecurityGroupEgressRequest egressRequest = new RevokeSecurityGroupEgressRequest()
				egressRequest.withIpPermissions(ipPermission)
				egressRequest.setGroupId(ruleConfig.securityGroupId)
				response = amazonClient.revokeSecurityGroupEgress(egressRequest)
			} else {
				RevokeSecurityGroupIngressRequest ingressRequest = new RevokeSecurityGroupIngressRequest()
				ingressRequest.withIpPermissions(ipPermission)
				ingressRequest.setGroupId(ruleConfig.securityGroupId)
				response = amazonClient.revokeSecurityGroupIngress(ingressRequest)
			}
			rtn.success = response.isReturn()
			log.debug("aws delete security group rule result: ${rtn.success}")
		} catch(e) {
			log.error("deleteSecurityGroupRule error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static deleteSubnet(Map opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			def networkId = opts.network.externalId
			if(networkId) {
				def networkRequest = new DeleteSubnetRequest().withSubnetId(networkId)
				amazonClient.deleteSubnet(networkRequest)
				rtn.success = true
			}
		} catch(e) {
			log.error("deleteSubnet error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static deleteVpc(opts) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = opts.amazonClient
			if(opts.vpcId) {
				def vpcRequest = new DeleteVpcRequest(opts.vpcId)
				amazonClient.deleteVpc(vpcRequest)
				rtn.success = true
			}
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				rtn.errorCode = awsError.getErrorCode()
				if(rtn.errorCode == 'DependencyViolation') {
					log.warn("deleteVpc - vpc has dependencies and can not be deleted: ${awsError}")
					rtn.msg = awsError.message
				} else {
					//not found - no big deal
					log.warn("deleteVpc - vpc not found: ${awsError}")
					rtn.success = true
				}
			} else {
				log.error("deleteVpc error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("deleteVpc error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn
	}

	static deleteRdsServer(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def serverId = opts.server.externalId
			def results = [success:true]
			if(serverId) {
				def serverRequest = new DeleteDBInstanceRequest().withDBInstanceIdentifier(opts.server.externalId).withSkipFinalSnapshot(true)
				def tmpResults = amazonClient.deleteDBInstance(serverRequest)
				if(opts.dbEngine && opts.dbEngine == "aurora-mysql") {
					def clusterRequest = new DeleteDBClusterRequest().withDBClusterIdentifier(opts.server.externalId).withSkipFinalSnapshot(true)
					def tmpClusterResults = amazonClient.deleteDBCluster(clusterRequest)
					rtn.clusterDelete = tmpClusterResults
				}
				rtn.instanceList = [tmpResults]
				results.success = rtn.instanceList?.size() > 0
			}
			rtn.success = results.success
		} catch(e) {
			log.error("stopServer error: ${e}", e)
		}
		return rtn
	}

	static deleteKeyPair(Map authConfig, String keyId) {
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = authConfig.amazonClient
			if(keyId) {
				def deleteRequest = new DeleteKeyPairRequest(keyId)
				def deleteResults = amazonClient.deleteKeyPair(deleteRequest)
				println("deleteResults: ${deleteResults}")
				rtn.success = true
			}
		} catch(e) {
			log.error("deleteKeyPair error: ${e}", e)
			rtn.msg = e.message
		}
		return rtn	
	}

	static addDiskOpts(diskType, volumeRequestSize, opts) {
		def retOpts = [:]
		// need to specify IOPS for io1 file types.  If not specified set to the minimum recommend 2:1
		// IOPS/GB ratio (http://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EBSVolumeTypes.html).  100
		// is the minimum allowable IOPS value, 20000 the max for this type
		opts.iops = opts.iops ?: opts.maxIOPS
		if((diskType == 'io1') && (opts.iops == null)) {
			retOpts.iops = opts.iops ?: volumeRequestSize * 2
			if(opts.iops < 100) {
				retOpts.iops = 100
			} else if(opts.iops > 20000) {
				retOpts.iops = 20000
			}
		}
		return retOpts
	}

	static addNetworkInterface(opts) {
		log.info("addNetworkInterface: ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def interfaceRequest = new CreateNetworkInterfaceRequest()
			def securityList = new LinkedList<String>()
			opts.securityGroups?.each { securityGroup ->
				securityList.add(securityGroup.id)
			}
			interfaceRequest.setSubnetId(opts.subnetId)
			interfaceRequest.setGroups(securityList)
			if (opts.ipAddress)
				interfaceRequest.privateIpAddress = opts.ipAddress
			rtn.networkInterface = amazonClient.createNetworkInterface(interfaceRequest).getNetworkInterface()
			rtn.success = rtn.networkInterface?.getNetworkInterfaceId() != null
		} catch(e) {
			rtn.error = e.getMessage()
			log.error("addNetworkInterface error: ${e}", e)
		}
		return rtn
	}

	static attachNetworkInterface(opts) {
		log.info("attachNetworkInterface: ${opts}")
		def rtn = [success:false]
		try {
			AmazonEC2Client amazonClient = (AmazonEC2Client)(opts.amazonClient)
			if(opts.networkInterfaceId && opts.serverId) {
				def serverDetail = getServerDetail(opts)
				if(serverDetail?.server) {
					def maxIndex = 0
					def networkList = serverDetail.server.getNetworkInterfaces()
					networkList?.each {
						if(it.getAttachment()?.getDeviceIndex() > maxIndex)
							maxIndex = it.getAttachment()?.getDeviceIndex()
					}
					def attachRequest = new AttachNetworkInterfaceRequest()
					attachRequest.setInstanceId(opts.serverId)
					attachRequest.setDeviceIndex(maxIndex + 1)
					attachRequest.setNetworkInterfaceId(opts.networkInterfaceId)
					rtn.attachmentId = amazonClient.attachNetworkInterface(attachRequest).getAttachmentId()
					NetworkInterfaceAttachmentChanges attachmentChanges = new NetworkInterfaceAttachmentChanges()
					attachmentChanges.withAttachmentId(rtn.attachmentId).withDeleteOnTermination(true)
					def deleteEniOnTerminateRequest = new ModifyNetworkInterfaceAttributeRequest()
					deleteEniOnTerminateRequest.withNetworkInterfaceId(opts.networkInterfaceId)
					deleteEniOnTerminateRequest.withAttachment(attachmentChanges)
					def results = amazonClient.modifyNetworkInterfaceAttribute(deleteEniOnTerminateRequest)
					rtn.success = rtn.attachmentId != null
				}
			}
		} catch(e) {
			log.error("attachNetworkInterface error: ${e}", e)
		}
		return rtn
	}

	static deleteNetworkInterface(opts) {
		log.info("deleteNetworkInterface: ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			if(opts.networkInterfaceId) {
				def interfaceRequest = new DeleteNetworkInterfaceRequest()
				interfaceRequest.setNetworkInterfaceId(opts.networkInterfaceId)
				def results = amazonClient.deleteNetworkInterface(interfaceRequest)
			}
			rtn.success = true
		} catch(e) {
			rtn.error = e.getMessage()
			log.error("deleteNetworkInterface error: ${e}", e)
		}
		return rtn
	}

	static detachNetworkInterface(opts) {
		log.info("detachNetworkInterface: ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			if(opts.attachmentId) {
				def detachRequest = new DetachNetworkInterfaceRequest()
				detachRequest.setAttachmentId(opts.attachmentId)
				detachRequest.setForce(true)
				def results = amazonClient.detachNetworkInterface(detachRequest)
				rtn.success = true
			}
		} catch(e) {
			log.error("detachNetworkInterface error: ${e}", e)
		}
		return rtn
	}

	static addVolume(opts) {
		log.info "addVolume : $opts"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def availabilityId = opts.availabilityId
			def diskType = opts.diskType ?: 'gp2'
			def size = opts.size
			def snapshotId = opts.snapshotId
			CreateVolumeRequest volumeRequest = new CreateVolumeRequest()
			volumeRequest.setAvailabilityZone(availabilityId)
			volumeRequest.setVolumeType(diskType)
			if(opts.kmsKeyId) {
				volumeRequest.setEncrypted(true)
				volumeRequest.setKmsKeyId(opts.kmsKeyId)
			}
			
			if(size)
				volumeRequest.setSize(opts.size?.toInteger() ?: 1)
			if(snapshotId) {
				volumeRequest.setSnapshotId(snapshotId)
			} else if(opts.encryptEbs) {
				volumeRequest.setEncrypted(true)
			}
			opts += addDiskOpts(diskType, volumeRequest.size, opts)
			if(opts.iops != null && diskType == 'io1')
				volumeRequest.iops = opts.iops
			rtn.volume = amazonClient.createVolume(volumeRequest).getVolume()
			//tag it
			def nameTag = new com.amazonaws.services.ec2.model.Tag('Name', opts.name ? opts.name : "morpheus node data")
			def resourceList = new LinkedList<String>()
			resourceList.add(rtn.volume.getVolumeId())
			def tagList = new LinkedList<com.amazonaws.services.ec2.model.Tag>()
			tagList.add(nameTag)
			def tagRequest = new CreateTagsRequest(resourceList, tagList)
			def tagResults = amazonClient.createTags(tagRequest)
			rtn.success = rtn.volume != null
		} catch(e) {
			rtn.error = e.getMessage()
			log.error("addVolume error: ${e}", e)
		}
		return rtn
	}

	static detachVolume(opts) {
		log.info "detachVolume: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def volumeId = opts.volumeId
			def instanceId = opts.instanceId

			def detachVolumeRequest = new DetachVolumeRequest()
			detachVolumeRequest.instanceId = instanceId
			detachVolumeRequest.volumeId = volumeId
			amazonClient.detachVolume(detachVolumeRequest)
			rtn.success = waitForVolumeState([volumeId: volumeId, requestedState: 'available', amazonClient: amazonClient])?.success
		} catch(e) {
			if(e.errorCode == 'IncorrectState') {
				// Probably already detached... don't error
				rtn.success = true
				log.warn("detachVolume error: ${e}")
			} else {
				log.error("detachVolume error: ${e}", e)
			}
		}
		return rtn
	}

	static snapshotVolume(opts) {
		log.debug "snapshotVolume: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def volumeId = opts.volumeId
			def instanceId = opts.instanceId
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmss")
			dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"))
			def dateStr = dateFormat.format(new Date())
			def description = "snapshot--${instanceId}--${volumeId}--${dateStr}"
			CreateSnapshotRequest snapshotRequest = new CreateSnapshotRequest(volumeId, description)
			CreateSnapshotResult snapshotResult = amazonClient.createSnapshot(snapshotRequest)
			def snapshot = snapshotResult.snapshot
			rtn.snapshotId = snapshot.snapshotId
			rtn.volumeSize = snapshot.volumeSize
			rtn.volumeId = snapshot.volumeId
			log.info("snapshot created [${rtn.snapshotId}, ${description}]")
			rtn.success = true
		} catch(e) {
			log.error("snapshotVolume error: ${e}", e)
		}
		return rtn
	}

	static deleteSnapshot(opts) {
		log.debug "deleteSnapshot: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def snapshotId = opts.snapshotId
			DeleteSnapshotRequest deleteSnapshotRequest  = new DeleteSnapshotRequest(snapshotId)
			amazonClient.deleteSnapshot(deleteSnapshotRequest)
			log.info("deleted snapshot ${snapshotId}")
			rtn.success = true
		} catch(e) {
			log.error("deleteSnapshot error: ${e}", e)
		}
		return rtn
	}

	static validateRdsServerConfig(Map opts=[:]) {
		def rtn = [success:false, errors: []]
		try {
			// Validate enough Elastic IPs left
			if(!opts.securityId) {
				if(opts.securityGroups) {
					if(opts.securityGroups.findAll {it.id != ''}?.size < 1)
						rtn.errors += [field:'securityGroup', msg: 'You must choose a security group']
				} else {
					rtn.errors += [field:'securityId', msg: 'You must choose a security group']
				}
			}
			if(!opts.dbSubnetGroup) {
				rtn.errors += [field:'dbSubnetGroup', msg: 'You must choose a subnet group']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug "validateServer results: ${rtn}"
		} catch(e)  {
			log.error "error in validateServerConfig: ${e}", e
		}
		return rtn
	}

	static validateServerConfig(MorpheusContext morpheusContext, Map opts =[:]) {
		def rtn = [success:false, errors: []]
		try {
			def cloud = morpheusContext.async.cloud.getCloudById(opts.zoneId?.toLong()).blockingGet()
			AmazonEC2 amazonClient = opts.amazonClient ?: getAmazonClient(cloud,false, null)
			// Validate enough Elastic IPs left
			if(opts.publicIpType == 'elasticIp') {
				def elasticIPsResult = listElasticIPs([amazonClient: amazonClient, cloud: cloud])
				def currentCount = elasticIPsResult.elasticIPs?.size() ?: 0
				log.debug "currentCount: ${currentCount}"
				def accountAttributesResult = listAccountAttributes([amazonClient: amazonClient])
				// Setting for # of allowed elastic IPs is defined on VPC and classic.. determine which one to use
				def maxElasticIPs = 0
				def attributeValue
				if(cloud.getConfigProperty('vpc') ) {
					attributeValue = accountAttributesResult.results?.accountAttributes?.find { it.attributeName == 'vpc-max-elastic-ips' }?.attributeValues?.attributeValue
				} else {
					attributeValue = accountAttributesResult.results?.accountAttributes?.find { it.attributeName == 'max-elastic-ips' }?.attributeValues?.attributeValue
				}
				if(attributeValue && attributeValue.size() > 0) {
					maxElasticIPs = attributeValue.first()?.toLong()
				}
				log.debug "maxElasticIPs: ${maxElasticIPs}"
				if(maxElasticIPs < currentCount + 1) {
					rtn.errors += [field: 'publicIpType', msg: 'Not enough Elastic IPs available']
				}
			}
			if(cloud?.configMap?.vpc) {
				if(!opts.subnetId) {
					if(opts.networkInterfaces?.size() > 0) {
						def hasNetwork = true
						def sameNetwork = true
						def matchNetwork
						opts.networkInterfaces?.each {
							log.debug("availability cloud: ${it.network.availabilityZone}")
							if(it.network.group != null) {
								hasNetwork = true
							} else if(it.network.id == null || it.network.id == '') {
								hasNetwork = false
							} else {
								def networkId
								def networkObj
								networkId = it.network.id.toLong()
								try {
									networkObj = morpheusContext.async.network.listById([networkId]).firstOrError().blockingGet()
								} catch(e) {
									log.error("Error finding network ${it.network.name}")
								}

								if(matchNetwork == null) {
									matchNetwork = networkObj.availabilityZone
								} else {
									if(networkObj.availabilityZone != matchNetwork)
										sameNetwork = false
								}
							}
						}
						if(!(opts.resourcePool || opts.resourcePoolId)) {
							rtn.errors += [field:'resourcePoolId', msg: 'You must choose a VPC']
						}
						if(hasNetwork != true)
							rtn.errors += [field:'networkInterface', msg:'You must choose a subnet for each network']
						if(sameNetwork != true)
							rtn.errors += [field:'networkInterface', msg:'You must choose subnets in the same availability cloud']
					} else {
						rtn.errors += [field:'subnetId', msg:'You must choose a subnet']
					}
				}
				if(!opts.securityId) {
					if(opts.securityGroups) {
						if(opts.securityGroups.findAll {it?.id != ''}?.size < 1)
							rtn.errors += [field:'securityGroup', msg: 'You must choose a security group']
					} else {
						rtn.errors += [field:'securityId', msg: 'You must choose a security group']
					}
				}
			}
			else if(!opts.availabilityId) {
				rtn.errors += [field: 'availabilityId', msg: 'You must choose a cloud']
			}
			if(opts.imageType && !opts.isMigration) {
				if(opts.imageType == 'private' && !opts.imageId) {
					rtn.errors += [field: 'imageId', msg: 'You must choose an image']
				} else if(opts.imageType == 'public' && !opts.publicImageId) {
					rtn.errors += [field: 'publicImageId', msg: 'You must choose an image']
				} else if(opts.imageType == 'local' && !opts.localImageId) {
					rtn.errors += [field: 'localImageId', msg: 'You must choose an image']
				}
			}
			if(opts.containsKey('nodeCount') && opts.nodeCount == ''){
				rtn.errors+= [field:'config.nodeCount', msg: 'Cannot be blank']
			}
			rtn.success = (rtn.errors.size() == 0)
			log.debug("validateServerConfig results: ${rtn}")
		} catch(e)  {
			log.error "error in validateServerConfig: ${e}", e
		}
		return rtn
	}

	static resizeVolume(opts) {
		log.debug "resizeVolume: ${opts}"
		def rtn = [success:false]
		try {
			def newSizeGb = opts.size
			def serverId = opts.server.externalId
			def volumeResult = getVolumeDetail([volumeId: opts.volumeId, amazonClient: opts.amazonClient])
			def originalVolume = volumeResult.volume
			def availabilityZone = originalVolume.getAvailabilityZone()
			def diskType = originalVolume.getVolumeType()
			def deviceName = originalVolume.getAttachments()?.find { it.instanceId == serverId }?.getDevice()
			def iops = 0
			if(diskType == 'io1') {
				iops = originalVolume.getIops()
				if(opts.iops) {
					iops = opts.iops
				}	
			}

			// Detach it
			def detachResult = detachVolume([volumeId: opts.volumeId, instanceId: serverId, amazonClient: opts.amazonClient])
			if(!detachResult.success) {
				throw new Exception("Error in detaching volume: ${detachResult}")
			}
			// Snapshot it
			def snapshotResult = snapshotVolume([volumeId: opts.volumeId, instanceId: serverId, amazonClient: opts.amazonClient])
			if(!snapshotResult.success) {
				throw new Exception("Error in snapshotting volume: ${snapshotResult}")
			}
			def waitResults = waitForSnapshot([snapshotId: snapshotResult.snapshotId, amazonClient: opts.amazonClient])
			if(!waitResults.success) {
				throw new Exception("Snapshot never finished: ${waitResults}")
			}
			// Create a volume from the snapshot
			def addVolumeResult = addVolume([size: newSizeGb, availabilityId: availabilityZone, name: opts.name, diskType: diskType, iops: iops,
				snapshotId:snapshotResult.snapshotId, amazonClient:opts.amazonClient])
			if(!addVolumeResult.success) {
				throw new Exception("Error in creating new volume: ${addVolumeResult}")
			}
			def newVolumeId = addVolumeResult.volume.volumeId
			rtn.newVolumeId = newVolumeId
			def checkReadyResult = checkVolumeReady([volumeId:newVolumeId, amazonClient:opts.amazonClient])
			if(!checkReadyResult.success) {
				throw new Exception("Volume never became ready: ${checkReadyResult}")
			}
			// Attach the new one
			def attachResults = attachVolume([volumeId:newVolumeId, serverId: serverId, amazonClient:opts.amazonClient, deviceName: deviceName])
			if(!attachResults.success) {
				throw new Exception("Volume failed to attach: ${attachResults}")
			}
			


			
			if(opts.deleteOriginalVolumes) {
				// Request to delete the volume (so we should keep the snapshot.. just in case)
				def deleteResults = deleteVolume([volumeId: opts.volumeId, amazonClient: opts.amazonClient])
				if (!deleteResults.success) {
					log.error "Error in deleting original volume during resize: ${deleteResults}"
				}
			} else {
				// Request to keep the original volume (so we should DELETE the snapshot)
				deleteSnapshot([snapshotId: snapshotResult.snapshotId, amazonClient: opts.amazonClient])
			}
			rtn.success = true
		} catch(e) {
			log.error("resizeVolume error: ${e}", e)
		}
		return rtn
	}

	static resizeInstance(opts) {
		log.debug "resizeInstance : $opts"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def resizeRequest = new ModifyInstanceAttributeRequest()
			resizeRequest.setInstanceId(opts.server.externalId)
			resizeRequest.setInstanceType(opts.flavorId)
			def results = amazonClient.modifyInstanceAttribute(resizeRequest)
			rtn.success = true
		} catch(e) {
			log.error("resizeInstance error: ${e}", e)
		}
		return rtn
	}

	static resizeRdsInstance(opts) {
		log.debug "resizeInstance : $opts"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def resizeRequest = new ModifyDBInstanceRequest()
			resizeRequest.setDBInstanceIdentifier(opts.server.externalId)
			resizeRequest.setDBInstanceClass(opts.instanceClass)
			if(opts.diskSize) {
				resizeRequest.setAllocatedStorage(opts.diskSize.toInteger())	
			}
			resizeRequest.setApplyImmediately(true)
			def results = amazonClient.modifyDBInstance(resizeRequest)
			rtn.success = true
		} catch(e) {
			rtn.msg = "An error occurred attempting to resize RDS Instance: ${e.message}"
			log.error("resizeRds error: ${e}", e)
		}
		return rtn
	}

	static deleteVolumesForInstanceOnTermination(enabled, opts) {
		log.debug("deleteVolumesForInstanceOnTermination : ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def specifications = opts.server.volumes?.collect { volume ->
				log.debug("Adding Specification For ${volume} - ${volume.deviceName}")
				EbsInstanceBlockDeviceSpecification ebsSpecification = new EbsInstanceBlockDeviceSpecification().withDeleteOnTermination(enabled)
				new InstanceBlockDeviceMappingSpecification()
					.withDeviceName(volume.deviceName)
					.withEbs(ebsSpecification)
			}
			if(specifications) {
				def deleteVolsOnTerminateRequest = new ModifyInstanceAttributeRequest()
				deleteVolsOnTerminateRequest.setInstanceId(opts.server.externalId)
				deleteVolsOnTerminateRequest.setBlockDeviceMappings(specifications)
				def results = amazonClient.modifyInstanceAttribute(deleteVolsOnTerminateRequest)	
			}
			rtn.success = true
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				//not found - no big deal
				log.warn("deleteVolumesForInstanceOnTermination - disk not found: ${awsError}")
			} else {
				log.error("deleteVolumesForInstanceOnTermination error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("deleteVolumesForInstanceOnTermination error: ${e}", e)	
		}
		return rtn
	}


	static deleteVolumeForInstanceOnTermination(String deviceName, opts) {
		log.debug("deleteVolumesForInstanceOnTermination : ${opts}")
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def specifications = []
			EbsInstanceBlockDeviceSpecification ebsSpecification = new EbsInstanceBlockDeviceSpecification().withDeleteOnTermination(true)
			specifications << new InstanceBlockDeviceMappingSpecification()
					.withDeviceName(deviceName)
					.withEbs(ebsSpecification)
			if(specifications) {
				def deleteVolsOnTerminateRequest = new ModifyInstanceAttributeRequest()
				deleteVolsOnTerminateRequest.setInstanceId(opts.serverId)
				deleteVolsOnTerminateRequest.setBlockDeviceMappings(specifications)
				def results = amazonClient.modifyInstanceAttribute(deleteVolsOnTerminateRequest)	
			}
			rtn.success = true
		} catch(com.amazonaws.services.ec2.model.AmazonEC2Exception awsError) {
			if(awsError.getStatusCode() == 400) {
				//not found - no big deal
				log.warn("deleteVolumesForInstanceOnTermination - disk not found: ${awsError}")
			} else {
				log.error("deleteVolumesForInstanceOnTermination error: ${awsError}", awsError)
			}
		} catch(e) {
			log.error("deleteVolumesForInstanceOnTermination error: ${e}", e)	
		}
		return rtn
	}

	static attachVolume(opts) {
		log.info "attachVolume: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			if(opts.deviceName) {
				def volumeRequest = new AttachVolumeRequest(opts.volumeId, opts.serverId, opts.deviceName)
				rtn.volume = amazonClient.attachVolume(volumeRequest).getAttachment()
				def waitAttachResults = waitForVolumeState([volumeId: opts.volumeId, requestedState: 'in-use', amazonClient: opts.amazonClient])
				if(!waitAttachResults.success) {
					throw new Exception("Volume never attached: ${waitAttachResults}")
				}
				deleteVolumeForInstanceOnTermination(rtn.volume.device,opts)
				rtn.deviceRef = opts.deviceName
				rtn.success = rtn.volume != null
			} else {
				volumeDevices.each { tmpDevice ->
					if(rtn.success == false) {
						try {
							def volumeRequest = new AttachVolumeRequest(opts.volumeId, opts.serverId, tmpDevice)
							rtn.volume = amazonClient.attachVolume(volumeRequest).getAttachment()
							def waitAttachResults = waitForVolumeState([volumeId: opts.volumeId, requestedState: 'in-use', amazonClient: opts.amazonClient])
							if(!waitAttachResults.success) {
								throw new Exception("Volume never attached: ${waitAttachResults}")
							}
							deleteVolumeForInstanceOnTermination(rtn.volume.device,opts)
							rtn.deviceRef = tmpDevice
							rtn.success = rtn.volume != null
						} catch (com.amazonaws.AmazonServiceException e2) {
							if(e2.getErrorCode() != 'InvalidParameterValue') {
								throw e2
							} else {
								log.warn("disk is in use: ${tmpDevice} - trying another")
							}
						}
					}
				}
			}
		} catch(e) {
			log.error("attachVolume error: ${e}", e)
		}
		return rtn
	}

	static getFreeVolumeName(blockDeviceList, index = 0) {
		def rtn = [success:false, deviceName:null]
		volumeDevices.eachWithIndex { tmpDevice, tmpIndex ->
			if(tmpIndex >= index && rtn.success == false) {
				def match = false
				blockDeviceList.each { blockDevice ->
					def blockName = blockDevice.getDeviceName()
					def deviceSuffix = tmpDevice.substring(6)
					//log.debug("blockName: ${blockName} suffix: ${deviceSuffix}")
					if(blockName.endsWith(deviceSuffix))
						match = true
				}
				if(match == false) {
					rtn.deviceName = tmpDevice
					rtn.success = true
				}
			}
		}
		return rtn
	}

	static getVmNetworks(vm) {
		def rtn = []
		try {
			def nicList = vm.getNetworkInterfaces()
			nicList?.eachWithIndex { nic, index ->
				def newNic = [name:nic.getNetworkInterfaceId(), description:nic.getDescription(),
					id:nic.getNetworkInterfaceId(), macAddress:nic.getMacAddress(), privateIp:nic.getPrivateIpAddress(),
					privateDnsName:nic.getPrivateDnsName(), vpcId:nic.getVpcId(), subnetId:nic.getSubnetId(),
					externalId:nic.getNetworkInterfaceId(), privateIpList:nic.getPrivateIpAddresses(),
					deviceIndex:nic.getAttachment()?.getDeviceIndex(), //ipv6List:nic.getIpv6Addresses(), 
					publicIp:nic.getAssociation()?.getPublicIp(), publicDnsName:nic.getAssociation()?.getPublicDnsName(),
					attachmentId:nic.getAttachment()?.getAttachmentId(), type:'standard', row:index,
					groups:nic.getGroups(), status:nic.getStatus()]
				rtn << newNic
			}
		} catch(e) {
			log.error("getVmNetworks error: ${e}")
		}
		return rtn
	}

	static getVmVolumes(vm) {
		def rtn = []
		try {
			def diskList = vm.getBlockDeviceMappings()
			diskList?.each { disk ->
				def newDisk = [deviceName:disk.getDeviceName(), name:disk.getDeviceName(), status:disk.getEbs()?.getStatus(),
					externalId:disk.getEbs()?.getVolumeId(), volumeId:disk.getEbs()?.getVolumeId()]
				rtn << newDisk
			}
		} catch(e) {
			log.error("getVmVolumes error: ${e}")
		}
		return rtn
	}

	static getVolumeDetail(opts) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def volumeRequest = new DescribeVolumesRequest().withVolumeIds(new LinkedList<String>())
			volumeRequest.getVolumeIds().add(new String(opts.volumeId))
			def tmpVolumes = amazonClient.describeVolumes(volumeRequest).getVolumes()
			rtn.volume = tmpVolumes?.size() > 0 ? tmpVolumes.get(0) : null
			rtn.success = rtn.volume != null
		} catch(e) {
			log.error("getVolumeDetail error: ${e}", e)
		}
		return rtn
	}

	static deleteVolume(opts) {
		log.debug "deleteVolume: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def volumeRequest = new DeleteVolumeRequest().withVolumeId(opts.volumeId)
			amazonClient.deleteVolume(volumeRequest)
			rtn.success = true
		} catch(e) {
			log.error("deleteVolume error: ${e}", e)
		}
		return rtn
	}

	static checkVolumeReady(opts) {
		log.debug "checkVolumeReady: ${opts}"
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(1000l * 6l)
				def volumeDetail = getVolumeDetail(opts)
				if(volumeDetail.success == true && volumeDetail?.volume?.getState()) {
					def tmpState = volumeDetail.volume.getState().toLowerCase()
					if(tmpState == 'available' || tmpState == 'in-use') {
						rtn.success = true
						rtn.results = volumeDetail
						rtn.volumeState = tmpState
						pending = false
					} else if(tmpState == 'deleting' || tmpState == 'deleted' || tmpState == 'error') {
						rtn.error = true
						rtn.results = volumeDetail
						rtn.volumeState = tmpState
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 10)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static waitForVolumeState(opts){
		log.debug "waitForVolumeState: ${opts}"
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(15000l) // polling more often sometimes gives cached state
				def volumeDetail = getVolumeDetail(opts)
				if(volumeDetail.success == true && volumeDetail?.volume?.getState()) {
					def tmpState = volumeDetail.volume.getState().toLowerCase()
					if(tmpState == opts.requestedState) {
						rtn.success = true
						rtn.results = volumeDetail
						rtn.volumeState = tmpState
						pending = false
					}
				}
				attempts ++
				if(attempts > 480)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static getSnapshot(opts) {
		log.debug "getSnapshot: ${opts}"
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def snapshotId = opts.snapshotId

			DescribeSnapshotsRequest describeSnapshotsRequest = new DescribeSnapshotsRequest()
			describeSnapshotsRequest.setSnapshotIds([snapshotId])
			DescribeSnapshotsResult describeSnapshotsResult = amazonClient.describeSnapshots(describeSnapshotsRequest)
			def snapshots = describeSnapshotsResult.getSnapshots()

			if(snapshots.size() > 0) {
				rtn.snapshot = snapshots[0]
			}

			rtn.success = true
		} catch(e) {
			log.error("getSnapshot error: ${e}", e)
		}
		return rtn
	}

	static waitForSnapshot(opts){
		def rtn = [success:false]
		try {
			def pending = true
			def attempts = 0
			while(pending) {
				sleep(15000l)  // polling more often sometimes gives cached state
				def snapshotResults = getSnapshot(opts)
				if(snapshotResults.success == true && snapshotResults?.snapshot) {
					def progress = snapshotResults?.snapshot?.progress
					if(progress == '100%') {
						rtn.success = true
						pending = false
					}
				}
				attempts ++
				if(attempts > 480)
					pending = false
			}
		} catch(e) {
			log.error("An Exception Has Occurred: ${e.message}",e)
		}
		return rtn
	}

	static insertContainerImage(opts) {
		def rtn = [success:false]
		def currentList = listImages(opts)?.imageList
		def image = opts.image
		def match = currentList.find{it.name == image.name && it.disk_format == image.imageType}
		if(!match) {
			def insertOpts = [amazonClient:opts.amazonClient, storageProvider:opts.storageProvider, zone:opts.zone, name:image.name,
				imageSrc:image.imageSrc, minDisk:image.minDisk, minRam:image.minRam, imageType:image.imageType, containerType:image.containerType,
				imageFiles:image.imageFiles, cloudFiles:image.cloudFiles, cachePath:image.cachePath, platform:image.platform ?: 'linux', token:image.token]
			log.info("insertContainerImage: ${insertOpts}")
			def createResults = createImage(insertOpts)
			log.info("insertContainerImage results: ${createResults}")
			if(createResults.success == true) {
				//transfer api
				insertOpts.imageFiles = createResults.imageFiles
				def importResults = importImage(insertOpts)
				log.info("importResults: ${importResults}")
				if(importResults.success == true) {
					rtn.imageTaskId = importResults.imageTaskId
					//check ready
					def statusResults = checkImportImageReady(opts, rtn.imageTaskId)
					log.info("taskStatus: ${statusResults}")
					if(statusResults.success == true) {
						rtn.imageId = statusResults.imageId
						//def saveResults = saveAccountImage(opts.amazonClient, opts.zone, rtn.imageId)
						//rtn.image = saveResults.image
						rtn.success = true
					}
				}
			} else {
				rtn.msg = createResults.msg ?: createResults.error
			}
		} else {
			log.debug("using image: ${match.id}")
			rtn.imageId = match.id
			rtn.success = true
		}
		return rtn
	}

	static createImage(insertOpts) {
		def rtn = [success:false, imageFiles:[]]
		def storageProvider = insertOpts.storageProvider
		def providerConfig = storageProvider.getConfigProperties()
		def providerOptions = [provider:storageProvider.providerType] + providerConfig + [useGzip:true]
		providerOptions.chunkSize = 100l * 1024l * 1024l
		log.debug("providerOptions: ${providerOptions}")
		def providerMap = [
			providerType:storageProvider.providerType,
			bucketName:storageProvider.bucketName,
			storageProviderId:storageProvider.id,
			provider:KarmanProvider.create(providerOptions)
		]
		insertOpts.imageFiles?.each { sourceFile ->
			if(sourceFile.parent?.name == providerMap.bucketName) {
				println "no need to move the file, its already in s3"
				rtn.imageFiles << sourceFile
				rtn.success = true
			} else {
				def targetFilePath = insertOpts.imageName ?: insertOpts.name
				targetFilePath = targetFilePath + '/' + getCloudFileName(sourceFile.name)
				def targetFile = providerMap.provider[providerMap.bucketName][targetFilePath]
				if(targetFile.exists() && targetFile.getContentLength() == sourceFile.getContentLength()) {
					log.info("found existing image in s3 - source: ${sourceFile.name}")
					rtn.imageFiles << targetFile
					rtn.success = true
				} else {
					targetFile.setContentLength(sourceFile.getContentLength())
					log.info("transfering image to amazon - source: ${sourceFile.name} target: ${targetFile.name}")
					def progressStream = new ProgressInputStream(sourceFile.inputStream, sourceFile.getContentLength())
					if(insertOpts.progressCallback)
						progressStream.setProgressCallback(insertOpts.progressCallback)
					targetFile.setInputStream(progressStream)
					targetFile.save()
					log.info("done transfering image to amazon")
					sleep(1000l * 5l)
					rtn.imageFiles << targetFile
					rtn.success = true
				}
			}
		}
		return rtn
	}

	static importImage(importOpts) {
		def rtn = [success:false]
		def importRequest = new ImportImageRequest()
		importRequest.setArchitecture('x86_64')
		//importRequest.setClientData(ClientData clientData)
		importRequest.setClientToken(importOpts.token)
		importRequest.setDescription(importOpts.imageName ?: importOpts.name)
		def diskList = new LinkedList<ImageDiskContainer>()
		importOpts.imageFiles?.eachWithIndex { imageFile, index ->
			def newDisk = new ImageDiskContainer()
			//newDisk.setDescription(String description)
			newDisk.setDeviceName(getDiskDevice(index))
			newDisk.setFormat(getAmazonImageFormat(importOpts.imageType))
			//newDisk.setSnapshotId()
			newDisk.setUrl()
			def diskBucket = new UserBucket()
			diskBucket.setS3Bucket(importOpts.storageProvider.bucketName)
			diskBucket.setS3Key(imageFile.name)
			newDisk.setUserBucket(diskBucket)
			diskList.add(newDisk)
		}
		importRequest.setDiskContainers(diskList)
		importRequest.setHypervisor('xen')
		importRequest.setLicenseType('BYOL')
		importRequest.setPlatform(getAmazonPlatform(importOpts.platform))
		def importResults = importOpts.amazonClient.importImage(importRequest)
		log.info("importResults: ${importResults}")
		rtn.imageTaskId = importResults.getImportTaskId()
		rtn.success = true
		return rtn
	}

	static insertSnapshotImage(snapshotOpts) {
		def rtn = [success:false]
		snapshotOpts.imageName = snapshotOpts.imageName ?: "ami-${snapshotOpts.snapshotId}"
		def currentList = listSnapshotImages(snapshotOpts)?.imageList
		def match = currentList.find{it.name == snapshotOpts.imageName}
		if(!match) {
			def registerResults = registerImageFromSnapshot(snapshotOpts)
			if(registerResults.success == true) {
				rtn.success = true
				rtn.imageId = registerResults.imageId
			} else {
				rtn.msg = registerResults.msg ?: registerResults.error
			}
		} else {
			log.debug("using image: ${match.imageId}")
			rtn.imageId = match.imageId
			rtn.success = true
		}
		log.debug("Insert snapshot image results: ${rtn}")
		return rtn
	}

	static registerImageFromSnapshot(snapshotOpts){
		log.info("registerImageFromSnapshot ${snapshotOpts}")
		def rtn = [success:false]
		def registerImageRequest = new RegisterImageRequest()
		registerImageRequest.setName(snapshotOpts.imageName)
		registerImageRequest.setRootDeviceName(snapshotOpts.deviceName)
		registerImageRequest.setArchitecture('x86_64')
		registerImageRequest.setVirtualizationType('hvm')
		def blockDeviceMappings = new LinkedList<BlockDeviceMapping>()

		//block device mapping for the root disk
		def blockDeviceMapping = new BlockDeviceMapping()
		blockDeviceMapping.setDeviceName(snapshotOpts.deviceName)
		def ebsDevice = new EbsBlockDevice()
		ebsDevice.setSnapshotId(snapshotOpts.snapshotId)
		ebsDevice.setVolumeSize(snapshotOpts.volumeSize?.toInteger())
		ebsDevice.setVolumeType(snapshotOpts.volumeType)
		if(snapshotOpts.volumeType == "io1")
			ebsDevice.setIops(snapshotOpts.iops)
		ebsDevice.setDeleteOnTermination(true)
		blockDeviceMapping.setEbs(ebsDevice)
		blockDeviceMappings.add(blockDeviceMapping)
		registerImageRequest.setBlockDeviceMappings(blockDeviceMappings)
		def registerImageResult = snapshotOpts.amazonClient.registerImage(registerImageRequest)
		log.info("registerImageResult: ${registerImageResult}")
		rtn.success = true
		rtn.imageId = registerImageResult.getImageId()
		if(snapshotOpts.shareUserId) {
			log.info("Sharing Image to Account: ${snapshotOpts.shareUserId}") 
			ModifyImageAttributeRequest imageModifyRequest = new ModifyImageAttributeRequest().withImageId(rtn.imageId)
			imageModifyRequest.withLaunchPermission(new LaunchPermissionModifications().withAdd(new LaunchPermission().withUserId(snapshotOpts.shareUserId)))
			imageModifyRequest.setUserIds([snapshotOpts.shareUserId])

			snapshotOpts.amazonClient.modifyImageAttribute(imageModifyRequest)
		}
		return rtn
	}

	static getImportTaskDetail(opts, taskId) {
		def rtn = [success:false]
		try {
			def amazonClient = opts.amazonClient
			def taskRequest = new DescribeImportImageTasksRequest()
			def taskIdList = new LinkedList<String>()
			taskIdList.add(taskId)
			taskRequest.setImportTaskIds(taskIdList)
			def taskResults = amazonClient.describeImportImageTasks(taskRequest)
			rtn.taskList = taskResults.getImportImageTasks()
			rtn.task = rtn.taskList?.size() > 0 ? rtn.taskList.first() : null
			if(rtn.task) {
				rtn.status = rtn.task.getStatus()
				rtn.statusMessage = rtn.task.getStatusMessage()
				rtn.progress = rtn.task.getProgress()
				rtn.imageId = rtn.task.getImageId()
				rtn.success = true
				log.debug("taskDetail: ${rtn}")
			}
		} catch(e) {
			log.error("getImportTaskDetail error: ${e}", e)
		}
		return rtn
	}

	//elastic container service
	
	
	//route 53 stuff
	static listDnsHostedZones(amazonClient) {
		def rtn = [success:false, zoneList:[]]
		try {
			def request = new ListHostedZonesRequest()
			request.setMaxItems('100')
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.listHostedZones(request)
				rtn.zoneList << results?.getHostedZones()

				if(results.isTruncated()) {
					request.setMarker(results.getNextMarker())
				} else {
					hasMore = false
				}
			}
			rtn.zoneList = amazonClient.listHostedZones()?.getHostedZones()
			rtn.success = true
		} catch(e) {
			log.error("listDnsHostedZones error: ${e}", e)
		}
		return rtn
	}

	static listDnsZoneRecords(amazonClient, String zoneId) {
		def rtn = [success:false, recordList:[]]
		try {
			def request = new ListResourceRecordSetsRequest()
			request.setHostedZoneId(zoneId)
			request.setMaxItems('100')
			def hasMore = true
			while(hasMore == true) {
				def results = amazonClient.listResourceRecordSets(request)
				results?.getResourceRecordSets()?.each {
					def row = [raw:it, name:it.getName(), type:it.getType(), ttl:it.getTTL(),
						region:it.getRegion(), records:it.getResourceRecords()]
					row.externalId = row.type + '-' + row.name + '-' + row.region
					if(row.records?.size() > 0) {
						row.recordsData = row.records.collect{ it.getValue() }
						row.record = row.records.first()
						row.recordData = row.record.getValue()
					}
					rtn.recordList << row
				}
				if(results.isTruncated()) {
					request.setStartRecordName(results.getNextRecordName())
				} else {
					hasMore = false
				}
			}
			rtn.success = true
		} catch(e) {
			log.error("listDnsZoneRecords for hosted zone ${zoneId} error: ${e}", e)
		}
		return rtn
	}

	static createDnsRecord(amazonClient, fqdn, zoneId, type, target) {
		def rtn = [success:false]
		try {
			//new record
			def resourceRecord = new ResourceRecord(target)
			//the list of records
			def resourceRecordList = []
			resourceRecordList << resourceRecord
			//the record set
			def resourceRecordSet = new ResourceRecordSet()
			def recordType = getRecordTypeForCode(type)
			resourceRecordSet.setName(fqdn)
			resourceRecordSet.setResourceRecords(resourceRecordList)
			resourceRecordSet.setType(recordType)
			resourceRecordSet.setTTL(60l)
			//change
			def change = new Change(ChangeAction.UPSERT, resourceRecordSet)
			//change list
			def changeList = []
			changeList << change
			//change batch
			def changeBatch = new ChangeBatch(changeList)
			//record request
			def changeRequest = new ChangeResourceRecordSetsRequest(zoneId, changeBatch)
			//create it
			def changeResult = amazonClient.changeResourceRecordSets(changeRequest)
			rtn.success = (changeResult?.getChangeInfo()?.getStatus() == 'PENDING' || changeResult?.getChangeInfo()?.getStatus() == 'SUCCESS')
			log.info("changeResult: ${changeResult}")
		} catch(e) {
			rtn.success = false
			rtn.errors = [error: "Error creating record: ${e.message}"]
			log.error("createDnsRecord error: ${e}", e)
		}
		return rtn
	}

	static deleteDnsRecord(amazonClient, fqdn, zoneId, type, target) {
		def rtn = [success:false]
		try {
			//new record
			def resourceRecord = new ResourceRecord()
			resourceRecord.setValue(target)
			//the list of records
			def resourceRecordList = []
			resourceRecordList << resourceRecord
			//the record set
			def resourceRecordSet = new ResourceRecordSet()
			def recordType = getRecordTypeForCode(type)
			resourceRecordSet.setName(fqdn)
			resourceRecordSet.setResourceRecords(resourceRecordList)
			resourceRecordSet.setType(recordType)
			resourceRecordSet.setTTL(60l)
			//change
			def change = new Change(ChangeAction.DELETE, resourceRecordSet)
			//change list
			def changeList = []
			changeList << change
			//change batch
			def changeBatch = new ChangeBatch(changeList)
			//record request
			def changeRequest = new ChangeResourceRecordSetsRequest(zoneId, changeBatch)
			//create it
			def changeResult = amazonClient.changeResourceRecordSets(changeRequest)
			rtn.success = (changeResult?.getChangeInfo()?.getStatus() == 'PENDING' || changeResult?.getChangeInfo()?.getStatus() == 'SUCCESS')
			log.info("changeResult: ${changeResult}")
		} catch(e) {
			log.error("deleteDnsRecord error: ${e}")
		}
		return rtn
	}
	
	static getRecordTypeForCode(code) {
		def type = RRType.CNAME
		switch(code) {
			case 'A':
				type = RRType.A
				break
			case 'AAAA':
				type = RRType.AAAA
				break
			case 'MX':
				type = RRType.MX
				break
			case 'NS':
				type = RRType.NS
				break
			case 'PTR':
				type = RRType.PTR
				break
			case 'SOA':
				type = RRType.SOA
				break
			case 'TXT':
				type = RRType.TXT
				break
			default:
				type = RRType.CNAME
				break
		}
		return type
	}

	static getServerMetrics(Map opts, String serverId) {
		def rtn = [success:false]
		try {
			def metricList = ['CPUUtilization', 'NetworkIn', 'NetworkOut', 'MemoryUsed', 'DiskSpaceUsed']
			//requires cloudwatch scripts - 'MemoryUsed', 'SwapUsed', 'DiskSpaceUsed'
			//CPUCreditUsage - someday
			def endDate = new Date()
			def startDate = new Date(endDate.time - (1000l * 60l * 10l))
			def statistics = ['Average', 'Maximum']
			def dimensions = []
			def addDimension = new com.amazonaws.services.cloudwatch.model.Dimension()
			dimensions << addDimension.withName('InstanceId').withValue(serverId)
			//query
			def haveMetrics = false
			metricList?.each { metric ->
				def metricRequest = new GetMetricStatisticsRequest().withPeriod(300).withNamespace('AWS/EC2')
					.withMetricName(metric).withStartTime(startDate).withEndTime(endDate)
					.withStatistics(statistics).withDimensions(dimensions)
				def metricResults = opts.amazonClient.getMetricStatistics(metricRequest)
				def datapoint = metricResults.getDatapoints() && metricResults.getDatapoints().size() > 0 ? metricResults.getDatapoints().first() : null
				if(datapoint) {
					haveMetrics = true
					rtn[metric + 'Avg'] = datapoint.getAverage()
					rtn[metric + 'Max'] = datapoint.getMaximum()
				}
			}
			rtn.success = haveMetrics
		} catch(e) {
			log.error("getInstanceMetrics error: ${e}")
		}
		return rtn
	}


	static getServersMetrics(Map opts, serverIds) {
		def rtn = [success:false]
		try {
			def metricList = ['CPUUtilization', 'DiskReadOps', 'DiskWriteOps', 'NetworkIn', 'NetworkOut', 'MemoryUsed', 'DiskSpaceUsed']
			//requires cloudwatch scripts - 'MemoryUsed', 'SwapUsed', 'DiskSpaceUsed'
			//CPUCreditUsage - someday
			def endDate = new Date()
			def startDate = new Date(endDate.time - (1000l * 60l * 10l))
			def statistics = ['Average', 'Maximum']
			def haveMetrics = false
			while(serverIds.size() > 0) {
				def chunkedServerIds = serverIds.take(10)
				serverIds = serverIds.drop(10)
				def dimensions = []
				def addDimension = new com.amazonaws.services.cloudwatch.model.Dimension()
				for(serverId in chunkedServerIds) {
					dimensions << addDimension.withName('InstanceId').withValue(serverId)	
				}
				
				//query
				
				metricList?.each { metric ->
					def metricRequest = new GetMetricStatisticsRequest().withPeriod(300).withNamespace('AWS/EC2')
						.withMetricName(metric).withStartTime(startDate).withEndTime(endDate)
						.withStatistics(statistics).withDimensions(dimensions)
					def metricResults = opts.amazonClient.getMetricStatistics(metricRequest)
					def datapoints = metricResults.getDatapoints()
					println("metric: ${metric} datapoints.size: ${datapoints.size()}")
					datapoints.each { datapoint ->
						println " datapoint: ${datapoint}"
					}
					// if(datapoint) {
					// 	haveMetrics = true
					// 	rtn[metric + 'Avg'] = datapoint.getAverage()
					// 	rtn[metric + 'Max'] = datapoint.getMaximum()
					// }
				}
			}
			
			rtn.success = haveMetrics
		} catch(e) {
			log.error("getInstanceMetrics error: ${e}")
		}
		return rtn
	}

	static validateCloudFormationTemplate(amazonClient, templateJson) {
		def rtn = [success:false]
		try {
			ValidateTemplateRequest validateTemplateRequest = new ValidateTemplateRequest().withTemplateBody(new JsonOutput().toJson(templateJson))
			amazonClient.validateTemplate(validateTemplateRequest)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("validateCloudFormationTemplate error: ${e}")
		}
		return rtn
	}

	static getCloudFormationStack(amazonClient, stackName) {
		def rtn = [success:false]
		log.debug("getCloudFormationStack: ${stackName}")
		try {
			com.amazonaws.services.cloudformation.model.DescribeStacksRequest request = new com.amazonaws.services.cloudformation.model.DescribeStacksRequest().withStackName(stackName)
			rtn.stack = amazonClient.describeStacks(request).getStacks()?.get(0)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.info("getCloudFormationStack info: ${e}")
		}
		return rtn
	}

	static getCloudFormationStackEvents(amazonClient, stackName) {
		def rtn = [success:false]
		log.debug("getCloudFormationStackEvents: ${stackName}")
		try {
			com.amazonaws.services.cloudformation.model.DescribeStackEventsRequest request = new com.amazonaws.services.cloudformation.model.DescribeStackEventsRequest().withStackName(stackName)
			rtn.stackEvents = amazonClient.describeStackEvents(request).getStackEvents()
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("getCloudFormationStackEvents error: ${e}")
		}
		return rtn
	}

	static getCloudFormationStackTemplate(amazonClient, stackName) {
		def rtn = [success:false]
		log.debug("getCloudFormationStackTemplate: ${stackName}")
		try {
			com.amazonaws.services.cloudformation.model.GetTemplateRequest request = new com.amazonaws.services.cloudformation.model.GetTemplateRequest().withStackName(stackName)
			rtn.template = amazonClient.getTemplate(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("getCloudFormationStackTemplate error: ${e}")
		}
		return rtn
	}

	static updateCloudFormationStack(amazonClient, stackName, templateJson, parameters, capabilities=[], isYaml) {
		def rtn = [success:false]
		try {
			def payload
			if(isYaml) {
				payload = asCloudFormationYaml(templateJson)
			} else {
				payload = new JsonOutput().toJson(templateJson)
			}
			com.amazonaws.services.cloudformation.model.UpdateStackRequest request = new com.amazonaws.services.cloudformation.model.UpdateStackRequest().withStackName(stackName).withTemplateBody(payload)
			java.util.Collection<com.amazonaws.services.cloudformation.model.Parameter> awsParameters = new LinkedList<com.amazonaws.services.cloudformation.model.Parameter>()
			parameters.each { k, v ->
				awsParameters.add(new com.amazonaws.services.cloudformation.model.Parameter().withParameterKey(k).withParameterValue(v.toString()))
			}
			request.setParameters(awsParameters)
			if(capabilities) {
				request.setCapabilities(capabilities)
			}
			com.amazonaws.services.cloudformation.model.UpdateStackResult result = amazonClient.updateStack(request)
			rtn.stackId = result.getStackId()
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("updateCloudFormationStack error: ${e}")
		}
		return rtn
	}

	static createCloudFormationStack(amazonClient, stackName, templateJson, parameters, capabilities=[], isYaml) {
		def rtn = [success:false]
		try {
			def payload
			if(isYaml) {
				payload = asCloudFormationYaml(templateJson)
			} else {
				payload = new JsonOutput().toJson(templateJson)
			}
			com.amazonaws.services.cloudformation.model.CreateStackRequest request = new com.amazonaws.services.cloudformation.model.CreateStackRequest().withStackName(stackName).withTemplateBody(payload)
			java.util.Collection<com.amazonaws.services.cloudformation.model.Parameter> awsParameters = new LinkedList<com.amazonaws.services.cloudformation.model.Parameter>()
			parameters.each { k, v ->
				awsParameters.add(new com.amazonaws.services.cloudformation.model.Parameter().withParameterKey(k).withParameterValue(v != null ? v.toString() : ''))
			}
			request.setParameters(awsParameters)
			if(capabilities) {
				request.setCapabilities(capabilities)
			}
			com.amazonaws.services.cloudformation.model.CreateStackResult result = amazonClient.createStack(request)
			rtn.stackId = result.getStackId()
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("createCloudFormationStack error: ${e}")
		}
		return rtn
	}

	static getCloudFormationStackResources(amazonClient, stackName) {
		def rtn = [success:false]
		try {
			DescribeStackResourcesRequest request = new DescribeStackResourcesRequest().withStackName(stackName)
			rtn.resources = amazonClient.describeStackResources(request).getStackResources()
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("getCloudFormationStackResources error: ${e}")
		}
		return rtn
	}

	static deleteCloudFormationStack(amazonClient, stackName) {
		def rtn = [success:false]
		try {
			com.amazonaws.services.cloudformation.model.DeleteStackRequest request = new com.amazonaws.services.cloudformation.model.DeleteStackRequest().withStackName(stackName)
			amazonClient.deleteStack(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("deleteCloudFormationStack error: ${e}")
		}
		return rtn
	}

	// Begin - Auto Scaling Group

	static listScaleGroups(amazonClient, subnetExternalIds = null) {
		log.debug("listScaleGroups")
		def rtn = [success:false, groups: []]
		try {
			DescribeAutoScalingGroupsRequest serverRequest = new DescribeAutoScalingGroupsRequest()
					.withMaxRecords(100)

			def response = amazonClient.describeAutoScalingGroups(serverRequest)
			def tmpGroups = response.getAutoScalingGroups().findAll { AutoScalingGroup autoScalingGroup ->
				subnetExternalIds == null || subnetExternalIds.contains(autoScalingGroup.getVPCZoneIdentifier())
			}

			while(tmpGroups.size() > 0) {
				rtn.groups += tmpGroups
				def nextPageToken = response.getNextToken()
				if(!nextPageToken) {
					break
				}
				serverRequest = new DescribeAutoScalingGroupsRequest().withNextToken(nextPageToken)
				serverRequest.getMaxRecords(100)
				response = amazonClient.describeAutoScalingGroups(serverRequest)
				tmpGroups = response.getAutoScalingGroups().findAll { AutoScalingGroup autoScalingGroup ->
					subnetExternalIds == null || subnetExternalIds.contains(autoScalingGroup.getVPCZoneIdentifier())
				}
			}
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("listScaleGroups error: ${e}")
		}
		return rtn
	}

	static createAutoScaleGroup(amazonClient, String groupName, maxSize, String instanceId) {
		log.debug("createAutoScaleGroup: $groupName, $maxSize, $instanceId")
		def rtn = [success:false]
		try {
			CreateAutoScalingGroupRequest request = new CreateAutoScalingGroupRequest()
					.withAutoScalingGroupName(groupName)
					.withInstanceId(instanceId)
					.withMinSize(0)
					.withMaxSize(maxSize)
					.withDesiredCapacity(0)
			CreateAutoScalingGroupResult result = amazonClient.createAutoScalingGroup(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("createAutoScaleGroup error: ${e}")
		}
		return rtn
	}

	static updateAutoScaleGroup(amazonClient, String groupName, minCount, maxCount=null, setDesiredToMin=false) {
		log.debug("updateAutoScaleGroup: $groupName, $minCount, $maxCount")
		def rtn = [success:false]
		try {
			UpdateAutoScalingGroupRequest request = new UpdateAutoScalingGroupRequest()
				.withAutoScalingGroupName(groupName)
				.withMinSize(minCount)

			if(maxCount) {
				request.setMaxSize(maxCount)
			}
			if(setDesiredToMin) {
				request.setDesiredCapacity(minCount)
			}

			UpdateAutoScalingGroupResult result = amazonClient.updateAutoScalingGroup(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("updateAutoScaleGroup error: ${e}")
		}
		return rtn
	}

	static deleteAutoScaleGroup(amazonClient, String groupName) {
		log.debug("deleteAutoScaleGroup: $groupName")
		def rtn = [success:false]
		try {
			DeleteAutoScalingGroupRequest request = new DeleteAutoScalingGroupRequest()
				.withAutoScalingGroupName(groupName)
				.withForceDelete(true)

			DeleteAutoScalingGroupResult result = amazonClient.deleteAutoScalingGroup(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("deleteAutoScaleGroup error: ${e}")
		}
		return rtn
	}

	static deleteLaunchConfiguration(amazonClient, String launchConfigurationName) {
		log.debug("deleteLaunchConfiguration: $launchConfigurationName")
		def rtn = [success:false]
		try {
			DeleteLaunchConfigurationRequest request = new DeleteLaunchConfigurationRequest()
				.withLaunchConfigurationName(launchConfigurationName)

			DeleteLaunchConfigurationResult result = amazonClient.deleteLaunchConfiguration(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("deleteLaunchConfiguration error: ${e}")
		}
		return rtn
	}

	static getAutoScaleGroupPolicies(amazonClient, String groupName) {
		def rtn = [success:false]
		try {
			DescribePoliciesRequest request = new DescribePoliciesRequest()
					.withAutoScalingGroupName(groupName)
			DescribePoliciesResult result = amazonClient.describePolicies(request)
			rtn.policies = result.scalingPolicies
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("getAutoScaleGroupPolicies error: ${e}")
		}
		return rtn
	}

	static detachAllInstancesFromScaleGroup(amazonClient, groupName) {
		log.debug("detachAllInstancesFromScaleGroup: $groupName")
		def rtn = [success:false]
		try {
			AutoScalingGroup group = getAutoScaleGroup(amazonClient, groupName)?.group
			def existingInstancesIds = group.getInstances()?.collect { com.amazonaws.services.autoscaling.model.Instance instance ->
				instance.getInstanceId()
			}

			def removeIds = []
			existingInstancesIds?.each { existingId ->
				removeIds << existingId
			}

			DetachInstancesRequest request = new DetachInstancesRequest()
				.withAutoScalingGroupName(groupName)
				.withInstanceIds(removeIds)
				.withShouldDecrementDesiredCapacity(true)  // MUST be true (otherwise will spawn new instances)
			amazonClient.detachInstances(request)

			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("detachAllInstancesFromScaleGroup error: ${e}", e)
		}
		return rtn
	}

	static updateInstancesAndPropertiesForScaleGroup(amazonClient, groupName, instanceIds, groupMin, groupMax) {
		log.debug("updateInstancesAndPropertiesForScaleGroup: $groupName, $instanceIds")
		def rtn = [success:false]
		try {
			// First.. fetch the instances in the group
			AutoScalingGroup group = getAutoScaleGroup(amazonClient, groupName)?.group
			def existingInstancesIds = group.getInstances()?.collect { com.amazonaws.services.autoscaling.model.Instance instance ->
				instance.getInstanceId()
			}

			// See which instances need to be removed
			def removeIds = []
			existingInstancesIds?.each { existingId ->
				if(!instanceIds.contains(existingId)) {
					removeIds << existingId
				}
			}
			if(removeIds) {
				// To be safe... set the group min to 0 during removals, then restore to the setting specified
				def temporaryGroupMin = groupMin > existingInstancesIds.size() - removeIds.size()
				if(temporaryGroupMin) {
					updateAutoScaleGroup(amazonClient, groupName, 0)
				}

				DetachInstancesRequest request = new DetachInstancesRequest()
					.withAutoScalingGroupName(groupName)
					.withInstanceIds(removeIds)
					.withShouldDecrementDesiredCapacity(true)
				amazonClient.detachInstances(request)
			}

			// See which instances need to be added
			def addIds = []
			instanceIds?.each { instanceId ->
				if(!existingInstancesIds.contains(instanceId)) {
					addIds << instanceId
				}
			}
			if(addIds) {
				def temporaryGroupMax = groupMax < existingInstancesIds.size() + addIds.size()
				if(temporaryGroupMax) {
					updateAutoScaleGroup(amazonClient, groupName, groupMin, existingInstancesIds.size() + addIds.size())
				}

				AttachInstancesRequest request = new AttachInstancesRequest()
						.withInstanceIds(addIds)
						.withAutoScalingGroupName(groupName)
				amazonClient.attachInstances(request)
			}

			// Update the min/max settings
			updateAutoScaleGroup(amazonClient, groupName, groupMin, groupMax, true)

			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("updateInstancesAndPropertiesForScaleGroup error: ${e}", e)
		}
		return rtn
	}

	static registerTargetWithScaleGroup(amazonClient, groupName, targetGroup) {
		log.debug("registerTargetWithScaleGroup: $groupName, $targetGroup")
		def rtn = [success:false]
		try {
			AttachLoadBalancerTargetGroupsRequest request = new AttachLoadBalancerTargetGroupsRequest()
					.withAutoScalingGroupName(groupName)
					.withTargetGroupARNs(targetGroup.getTargetGroupArn())

			AttachLoadBalancerTargetGroupsResult response = amazonClient.attachLoadBalancerTargetGroups(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.error("registerTargetWithScaleGroup error: ${e}", e)
		}
		return rtn
	}

	static listAlbs(opts) {
		def rtn = [success:false, albList:[]]
		try {
			def amazonClient = opts.amazonClient
			DescribeLoadBalancersRequest albRequest = new DescribeLoadBalancersRequest()
			rtn.albList =  amazonClient.describeLoadBalancers(albRequest).getLoadBalancers()?.findAll { it.getType() == 'application' }
			rtn.success = true
		} catch(e) {
			log.debug("albList error: ${e}", e)
		}
		return rtn
	}

	static listElbs(opts) {
		def rtn = [success:false, elbList:[]]
		try {
			com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient amazonClient = opts.amazonClient as com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient
			com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest elbRequest = new com.amazonaws.services.elasticloadbalancing.model.DescribeLoadBalancersRequest()
			rtn.elbList =  amazonClient.describeLoadBalancers(elbRequest).getLoadBalancerDescriptions()
			rtn.success = true
		} catch(e) {
			log.debug("listElbs error: ${e}", e)
		}
		return rtn
	}

	static getAutoScaleGroup(amazonClient, groupName) {
		def rtn = [success:false]
		log.debug "getAutoScaleGroup: $groupName"
		try {
			DescribeAutoScalingGroupsRequest request = new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(groupName)
			rtn.group = amazonClient.describeAutoScalingGroups(request).getAutoScalingGroups()?.get(0)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("getAutoScaleGroup error: ${e}", e)
		}
		return rtn
	}

	static getLaunchConfiguration(amazonClient, launchConfigurationName) {
		def rtn = [success:false]
		log.debug "getLaunchConfiguration: $launchConfigurationName"
		try {
			DescribeLaunchConfigurationsRequest request = new DescribeLaunchConfigurationsRequest().withLaunchConfigurationNames(launchConfigurationName)
			rtn.group = amazonClient.describeLaunchConfigurations(request).getLaunchConfigurations()?.get(0)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("getLaunchConfiguration error: ${e}", e)
		}
		return rtn
	}

	static createOrUpdateAlarm(amazonClient, type, groupName, alarmName, threshold, comparison, policyARN) {
		def rtn = [success:false]
		log.debug "createOrUpdateAlarm: ${}"
		try {
			com.amazonaws.services.cloudwatch.model.Dimension dimension = new com.amazonaws.services.cloudwatch.model.Dimension()
				.withName("AutoScalingGroupName")
				.withValue(groupName)

			PutMetricAlarmRequest request = new PutMetricAlarmRequest()
					.withAlarmName(alarmName)
					.withComparisonOperator(comparison)
					.withEvaluationPeriods(1)
					.withMetricName(type == 'cpu' ? "CPUUtilization" : '')
					.withNamespace("AWS/EC2")
					.withPeriod(300)
					.withStatistic(com.amazonaws.services.cloudwatch.model.Statistic.Average)
					.withThreshold(threshold.toDouble())
					.withActionsEnabled(true)
					.withUnit(StandardUnit.Seconds)
					.withDimensions(dimension)
					.withTreatMissingData('ignore')
					.withAlarmActions(policyARN)

			PutMetricAlarmResult response = amazonClient.putMetricAlarm(request)

			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("createOrUpdateAlarm error: ${e}", e)
		}
		return rtn
	}

	static removeAlarm(amazonClient, alarmName) {
		log.debug "removeScalingPolicy: $alarmName"
		def rtn = [success:false]
		log.debug "removeAlarm: ${}"
		try {
			DeleteAlarmsRequest request = new DeleteAlarmsRequest()
				.withAlarmNames(alarmName)

			DeleteAlarmsResult response = amazonClient.deleteAlarms(request)

			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("removeAlarm error: ${e}", e)
		}
		return rtn
	}

	static createOrUpdateScalingPolicy(amazonClient, groupName, policyName) {
		def rtn = [success:false]
		log.debug "createOrUpdateScalingPolicy: ${}"
		try {
			PutScalingPolicyRequest request = new PutScalingPolicyRequest()
				.withAutoScalingGroupName(groupName)
				.withPolicyName(policyName)
				.withAdjustmentType('ChangeInCapacity')
				.withPolicyType('SimpleScaling')
				.withCooldown(300)
				.withScalingAdjustment(1)

			PutScalingPolicyResult response = amazonClient.putScalingPolicy(request)
			rtn.policyARN = response.policyARN
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("createOrUpdateScalingPolicy error: ${e}", e)
		}
		return rtn
	}

	static removeScalingPolicy(amazonClient, groupName, policyName) {
		def rtn = [success:false]
		log.debug "removeScalingPolicy: $groupName, $policyName"
		try {
			com.amazonaws.services.autoscaling.model.DeletePolicyRequest request = new com.amazonaws.services.autoscaling.model.DeletePolicyRequest()
					.withAutoScalingGroupName(groupName)
					.withPolicyName(policyName)

			com.amazonaws.services.autoscaling.model.DeletePolicyResult response = amazonClient.deletePolicy(request)
			rtn.success = true
		} catch(e) {
			rtn.msg = e.message
			log.debug("removeScalingPolicy error: ${e}", e)
		}
		return rtn
	}

	// End - auto scaling group

	//eks calls
	static listEksClusters(AmazonEKSClient amazonClient, Map opts) {
		def rtn = [success:false, clusters:[]]
		try {
			boolean done = false
			def clusterRequest = new com.amazonaws.services.eks.model.ListClustersRequest(maxResults: 100)
			while(!done) {
				ListClustersResult listClustersResult = amazonClient.listClusters(clusterRequest)
				def clusterNames = listClustersResult.getClusters()
				clusterNames?.each { name ->
					def itemRequest = new DescribeClusterRequest()
					itemRequest.setName(name)
					def item = amazonClient.describeCluster(itemRequest).getCluster()
					if (item)
						rtn.clusters << item
				}
				clusterRequest.setNextToken(listClustersResult.getNextToken())
				if(listClustersResult.getNextToken() == null) {
					done = true
				}
			}
			rtn.success = true
		} catch(e) {
			log.debug("listEksClusters error: ${e}", e)
		}
		return rtn
	}

	//s3
	static listBuckets(AmazonS3Client amazonClient, Map opts = [:]) {
		def rtn = [success:false, buckets:[]]
		try {
			rtn.buckets = amazonClient.listBuckets(new ListBucketsRequest())
			rtn.success = true
		} catch(e) {
			log.debug("listBuckets error: ${e}", e)
		}
		return rtn
	}

	static createBucket(AmazonS3Client amazonClient, String name) {
		def rtn = [success:false]
		try {
			rtn.bucket = amazonClient.createBucket(name)
			rtn.success = true
		}
		catch(e) {
			log.debug("createBucket error: ${e}", e)
			rtn.msg = e.errorMessage
		}
		rtn
	}


	static getBucketLocation(AmazonS3Client amazonClient, String name) {
		def rtn = [success:false]
		try {
			rtn.location = amazonClient.getBucketLocation(name)
			rtn.success = true
		}
		catch(e) {
			log.debug("createBucket error: ${e}", e)
			rtn.msg = e.errorMessage
		}
		rtn
	}

	static getBucketPolicy(AmazonS3 amazonClient, String bucketName) {
		def rtn = [success:false]
		try {
			rtn.bucketPolicy = amazonClient.getBucketPolicy(new GetBucketPolicyRequest().withBucketName(bucketName))
			rtn.success = true
		} catch(e) {
			log.debug("getBucketPolicy error: ${e}", e)
			rtn.msg = e.errorMessage
		}
		rtn
	}

	static setBucketPolicy(AmazonS3 amazonClient, String bucketName, String policyText) {
		def rtn = [success:false]
		try {
			amazonClient.setBucketPolicy(bucketName, policyText)
			rtn.success = true
		}
		catch(e) {
			log.error("setBucketPolicy error: ${e}", e)
			rtn.msg = e.message
		}
		rtn
	}

	//costing
	static getDimensionValues(AWSCostExplorerClient amazonClient, String dimension, DateInterval timePeriod, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new GetDimensionValuesRequest()
			awsRequest.withDimension(dimension)
			awsRequest.withTimePeriod(timePeriod)
			rtn.results = amazonClient.getDimensionValues(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getDimensionValues error: ${e}", e)
		}
		return rtn
	}

	static createCostingReportDefinition(AWSCostAndUsageReportClient amazonClient, Map opts) {
		def rtn = [success:false]
		try {
			def reportDefinition = new ReportDefinition()
				.withCompression(opts.compression ?: 'GZIP')
				.withFormat(opts.format ?: 'textORcsv')
				.withRefreshClosedReports(!!opts.refreshClosed)
				.withReportName(opts.name)
				.withS3Bucket(opts.bucket)
				.withS3Region(opts.region)
				.withS3Prefix(opts.prefix)
				.withTimeUnit(opts.timeUnit ?: 'HOURLY')

			if(opts.artifacts) {
				reportDefinition.withAdditionalArtifacts(opts.artifacts)
			}
			if(opts.extraElements) {
				reportDefinition.withAdditionalSchemaElements(opts.extraElements)
			}

			PutReportDefinitionRequest request = new PutReportDefinitionRequest().withReportDefinition(reportDefinition)
			rtn.results = amazonClient.putReportDefinition(request)
			rtn.success = true
		} catch(e) {
			if(e.errorCode == 'DuplicateReportNameException') {
				rtn.msg = "${opts.name} already exists"
			}
			else {
				rtn.msg = e.message
			}
			log.error("createCostingReport error: ${e}", e)
		}
		return rtn
	}

	static getCostingReportDefinitions(AWSCostAndUsageReportClient amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new DescribeReportDefinitionsRequest()
			rtn.results = amazonClient.describeReportDefinitions(awsRequest)
			rtn.success = true
		} catch(e) {
			// log.debug("unable to retrieve costing report definitions, checking billing policy")
		}
		return rtn
	}

	//sts calls
	static getClientIdentity(AWSSecurityTokenService amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def identityRequest = new GetCallerIdentityRequest()
			rtn.results = amazonClient.getCallerIdentity(identityRequest)
			rtn.success = true
		} catch(e) {
			log.debug("getClientIdentity error: ${e}", e)
		}
		return rtn
	}

	static getAccountChildren(AWSOrganizationsClient amazonClient, String accountId, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new ListChildrenRequest()
			awsRequest.setParentId('ou-' + accountId)
			awsRequest.setChildType('ACCOUNT')
			rtn.results = amazonClient.listChildren(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getAccountChildren error: ${e}", e)
		}
		return rtn
	}

	static getAccountParents(AWSOrganizationsClient amazonClient, String accountId, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new ListParentsRequest()
			awsRequest.withChildId(accountId)
			rtn.results = amazonClient.listParents(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getAccountParents error: ${e}", e)
		}
		return rtn
	}

	static getOrganization(AWSOrganizationsClient amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new DescribeOrganizationRequest()
			rtn.results = amazonClient.describeOrganization(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getOrganization error: ${e}", e)
		}
		return rtn
	}

	static getRoots(AWSOrganizationsClient amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new ListRootsRequest()
			rtn.results = amazonClient.listRoots(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getRoots error: ${e}", e)
		}
		return rtn
	}

	static getAccounts(AWSOrganizationsClient amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new ListAccountsRequest()
			rtn.results = amazonClient.listAccounts(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getAccounts error: ${e}", e)
		}
		return rtn
	}

	static getAmazonServices(AWSPricingClient amazonClient, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new DescribeServicesRequest()
			rtn.results = amazonClient.describeServices(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getAmazonServices error: ${e}", e) //huh
		}
		return rtn
	}

	static getAmazonServiceAttribute(AWSPricingClient amazonClient, String serviceCode, String attributeName, Map opts) {
		def rtn = [success:false, results:null]
		try {
			def awsRequest = new GetAttributeValuesRequest()
			awsRequest.withServiceCode(serviceCode)
			awsRequest.withAttributeName(attributeName)
			rtn.results = amazonClient.getAttributeValues(awsRequest)
			rtn.success = true
		} catch(e) {
			log.warn("getAmazonServiceAttribute error: ${e}", e) //huh
		}
		return rtn
	}

	static getAmazonPlatform(platform) {
		return platform?.toUpperCase() == 'WINDOWS' ? 'Windows' : 'Linux'
	}

	static getAmazonImageFormat(format) {
		if(format == 'vhd')
			return 'VHD'
		if(format == 'raw')
			return 'RAW'
		if(format == 'vmdk' || format == 'ovf')
			return 'VMDK'
		return null
	}

	static getDiskDevice(index) {
		return diskNames[index]
	}

	static getVolumeDevice(index) {
		return volumeDevices[index]
	}

	static getCloudFileName(fileName) {
		def rtn = fileName
		if(fileName?.indexOf('/') > 0)
			rtn = fileName.substring(fileName.lastIndexOf('/') + 1)
		return rtn
	}

	static generateKeyName(keyId) {
		return "morpheus-${keyId}-${KeyUtility.generateSimpleKey(6)}"
	}

	static decryptAmazonPassword(privateKeyData, passwordData) {
		def rtn = null
		log.debug("decryptAmazonPassword: ${passwordData}")
		try {
			privateKeyData = privateKeyData.replace("-----BEGIN RSA PRIVATE KEY-----\n", "")
			privateKeyData = privateKeyData.replace("-----BEGIN RSA PRIVATE KEY-----\r\n", "")
	  privateKeyData = privateKeyData.replace("-----END RSA PRIVATE KEY-----", "")
	  def keyBytes = Base64.decode(privateKeyData) //org.codehaus.groovy.runtime.EncodingGroovyMethods.decodeBase64(privateKeyData)
	  def keySpec = new PKCS8EncodedKeySpec(keyBytes)
	  def keyFactory = KeyFactory.getInstance('RSA')
	  def privateKey = keyFactory.generatePrivate(keySpec)
	  def cipher
			cipherTypeList.each { cyperType ->
				if(cipher == null) {
					try {
						cipher = Cipher.getInstance(cyperType)
					cipher.init(Cipher.DECRYPT_MODE, privateKey)
					} catch(e2) {
						cipher = null
						log.info("wrong key type: ${cyperType} - ${e2}")
					}
				}
			}
			if(cipher) {
				def cipherBytes = Base64.decode(passwordData) //org.codehaus.groovy.runtime.EncodingGroovyMethods.decodeBase64(passwordData)
			def plainText = cipher.doFinal(cipherBytes)
			rtn = new String(plainText, Charset.forName('ASCII'))
			}
		} catch(e) {
			log.error("error decrypting amazon password: ${e}", e)
		}
		return rtn
	}

	static isVPC(zone) {
		return zone?.getConfigProperty('vpc') != null
	}

	static uploadOrGetCert(AccountCertificate accountCert, Cloud zone) {
		log.debug "uploadOrGetCert: ${accountCert}"

		def amazonCertArn

		// Search through all the certs.. looking for one tagged with our cert id
		AWSCertificateManagerClient certClient = getAmazonCertificateClient(zone)
		ListCertificatesRequest certRequest = new ListCertificatesRequest()
		ListCertificatesResult listCertsResult = certClient.listCertificates(certRequest)
		listCertsResult.certificateSummaryList.each { CertificateSummary summary ->
			log.debug "Current cert arn: ${summary.certificateArn}"
			ListTagsForCertificateRequest listTagsRequest = new ListTagsForCertificateRequest()
			listTagsRequest.certificateArn = summary.certificateArn
			ListTagsForCertificateResult listTagsResult = certClient.listTagsForCertificate(listTagsRequest)
			listTagsResult.tags?.each { com.amazonaws.services.certificatemanager.model.Tag tag ->
				if (tag.key == 'morpheus-cert-id' && tag.value?.toString() == accountCert.id.toString()) {
					amazonCertArn = summary.certificateArn
					log.debug "found amazon cert arn ${amazonCertArn} for cert: ${accountCert.id}"
				}
			}
		}

		if (!amazonCertArn) {
			log.debug "No cert found tagged with cert id ${accountCert.id}.. creating new one"
			def certContent = this.morpheus.loadBalancer.certificate.getCertificateContent(accountCert).blockingGet()
			if(certContent.success != true) {
				log.error("Error fetching certificate... Perhaps a remote Cert Servie error? {}", certContent)
				return amazonCertArn
			}

			ImportCertificateRequest importCert = new ImportCertificateRequest()
			importCert.setCertificate(certContent.certFile?.bytes ? ByteBuffer.wrap(certContent.certFile?.bytes) : null)
			importCert.setCertificateChain(certContent.chainFile?.bytes ? ByteBuffer.wrap(certContent.chainFile?.bytes) : null)
			importCert.setPrivateKey(certContent.keyFile?.bytes ? ByteBuffer.wrap(certContent.keyFile?.bytes) : null)

			ImportCertificateResult importCertResult = certClient.importCertificate(importCert)
			amazonCertArn = importCertResult.certificateArn

			AddTagsToCertificateRequest addTagToCertRequest = new AddTagsToCertificateRequest().withTags(new LinkedList<com.amazonaws.services.certificatemanager.model.Tag>())
			addTagToCertRequest.certificateArn = amazonCertArn
			com.amazonaws.services.certificatemanager.model.Tag tag = new com.amazonaws.services.certificatemanager.model.Tag()
			tag.key = 'morpheus-cert-id'
			tag.value = accountCert.id
			addTagToCertRequest.tags.add(0, tag)
			certClient.addTagsToCertificate(addTagToCertRequest)
		}

		return amazonCertArn
	}

	static getAmazonAccessKey(zone) {
		def config = zone.getConfigMap()
		def useHostCredentials = getAmazonUseHostCredentials(zone)
		def rtn = useHostCredentials ? null : (zone.accountCredentialData?.username ?: config.accessKey)
		if(!useHostCredentials && !rtn) {
			throw new Exception('no amazon access key specified')
		}
		rtn
	}

	static getAmazonSecretKey(zone) {
		def config = zone.getConfigMap()
		def useHostCredentials = getAmazonUseHostCredentials(zone)
		def rtn = useHostCredentials ? null : (zone.accountCredentialData?.password ?: config.secretKey)
		if(!useHostCredentials && !rtn) {
			throw new Exception('no amazon secret key specified')
		}
		rtn
	}

	static getAmazonStsAssumeRole(zone) {
		def config = zone.getConfigMap()
		if(config?.stsAssumeRole) {
			return config.stsAssumeRole
		} else {
			return null
		}
	}


	static getAmazonStsExternalId(Cloud cloud) {
		def config = cloud.getConfigMap()
		if(config?.stsExternalId) {
			return config.stsExternalId
		} else {
			return null
		}
	}

	static getAmazonUseHostCredentials(Cloud cloud) {
		def config = cloud?.getConfigMap()
		if(config?.useHostCredentials) {
			return config.useHostCredentials in [true, 'true', 'on']
		} else {
			return false
		}
	}

	static String defaultEndpoint = "ec2.us-east-1.amazonaws.com"

	static String getAmazonEndpoint(Cloud cloud) {
		def config = cloud.getConfigMap()
		if(config?.endpoint) {
			return config.endpoint
		} else {
			return defaultEndpoint //default to us-east
		}
	}

	static String getAmazonEndpoint(AccountIntegration accountIntegration) {
		if(accountIntegration.serviceUrl) {
			return accountIntegration.serviceUrl
		} else {
			return defaultEndpoint //default to us-east
		}
	}

	static String getAmazonEndpointRegion(Cloud cloud) {
		getAmazonEndpointRegion(getAmazonEndpoint(cloud))
	}

	static String getAmazonEndpointRegion(String endpoint) {
		String rtn = endpoint
		if(endpoint) {
			def firstDot = endpoint.indexOf('.')
			if(firstDot >= 0) {
				rtn = rtn.substring(firstDot + 1)
				firstDot = rtn.indexOf('.')
				rtn = rtn.substring(0, firstDot)
			}
			
		}
		return rtn
	}

	static clients = [:]
	static clientLock = new Object()

	static getAmazonCredentials(Cloud zone, com.amazonaws.ClientConfiguration clientConfiguration,Boolean useCostingCreds=false, String desiredRegion=null) {
		def creds
		def credsProvider
		def rtn = [clientExpires:null,credentials:null]
		AWSSecurityTokenService sts
		try {
			if(useCostingCreds && zone.getConfigProperty('costingAccessKey')) {
				creds = new BasicAWSCredentials(zone.getConfigProperty('costingAccessKey'), zone.getConfigProperty('costingSecretKey'))	
				credsProvider = new AWSStaticCredentialsProvider(creds)
			} else if(getAmazonUseHostCredentials(zone)) {
				log.debug("using instance profile creds")
				credsProvider = new InstanceProfileCredentialsProvider()
			} else {
				creds = new BasicAWSCredentials(getAmazonAccessKey(zone), getAmazonSecretKey(zone))
				credsProvider = new AWSStaticCredentialsProvider(creds)
			}
			rtn.credsProvider = credsProvider
			def stsAssumeRole = getAmazonStsAssumeRole(zone)
			if(stsAssumeRole) {
				def endpoint = getAmazonEndpoint(zone)
				def region = desiredRegion ?: getAmazonEndpointRegion(endpoint)
				String externalId = getAmazonStsExternalId(zone)
				sts = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(region).build()
				AssumeRoleRequest roleRequest = new AssumeRoleRequest().withRoleArn(stsAssumeRole).withRoleSessionName('morpheus')
				if(externalId) {
					roleRequest.setExternalId(externalId)
				}
				AssumeRoleResult roleResult = sts.assumeRole(roleRequest)
				def roleCredentials = roleResult.credentials
				creds = new BasicSessionCredentials(roleCredentials.getAccessKeyId(), roleCredentials.getSecretAccessKey(), roleCredentials.getSessionToken());
				rtn.clientExpires = roleCredentials.getExpiration()
				rtn.credsProvider = new AWSStaticCredentialsProvider(creds)
				log.debug("Getting Fresh STS Credentials:  ${roleCredentials.getAccessKeyId()} -- expires: ${rtn.clientExpires}")
			} else {
				rtn.clientExpires = new Date(new Date().time + 30L*60000L)
			}
			rtn.credentials = creds
		} catch(ex) {
			log.error("Error acquiring Credentials ${ex.message}",ex)
		} finally {
			if(sts) {
				sts.shutdown()
			}
		}
		
		return rtn
	}

	static getAmazonCredentials(Map authConfig , com.amazonaws.ClientConfiguration clientConfiguration, String desiredRegion=null) {
		def creds
		def credsProvider
		def rtn = [clientExpires:null,credentials:null]
		AWSSecurityTokenService sts
		try {
			if(authConfig.useHostCredentials) {
				credsProvider = new InstanceProfileCredentialsProvider()
			} else {
				creds = new BasicAWSCredentials(authConfig.accessKey, authConfig.secretKey)	
				credsProvider = new AWSStaticCredentialsProvider(creds)
			}
			rtn.credsProvider = credsProvider
			def stsAssumeRole = authConfig.stsAssumeRole
			String externalId = authConfig.stsExternalId
			if(stsAssumeRole) {
				def region = desiredRegion ?: authConfig.region
				sts = AWSSecurityTokenServiceClientBuilder.standard().withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(region).build()
				AssumeRoleResult roleResult = sts.assumeRole(new AssumeRoleRequest().withRoleArn(stsAssumeRole).withRoleSessionName('morpheus').withExternalId(externalId))

				if(roleResult.credentials) {
					credsProvider = null
				}

				def roleCredentials = roleResult.credentials
				creds = new BasicSessionCredentials(roleCredentials.getAccessKeyId(), roleCredentials.getSecretAccessKey(), roleCredentials.getSessionToken());
				rtn.clientExpires = roleCredentials.getExpiration()
				rtn.credsProvider = new AWSStaticCredentialsProvider(creds)
				log.debug("Getting Fresh STS Credentials:  ${roleCredentials.getAccessKeyId()} -- expires: ${rtn.clientExpires}")
			} else {
				rtn.clientExpires = new Date(new Date().time + 30L*60000L)
			}
			rtn.credentials = creds
		} catch(ex) {
			log.error("Error acquiring Credentials ${ex.message}",ex)
		} finally {
			if(sts) {
				sts.shutdown()
			}
		}
		
		return rtn
	}

	static AmazonEC2 getAmazonClient(Cloud zone, Boolean fresh=false, String region=null) {
		def creds
		AWSCredentialsProvider credsProvider
		def clientInfo = getCachedClientInfo("cloud:${zone.id}:${region}",'client')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider as AWSCredentialsProvider
		}
		com.amazonaws.ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires

		if(!creds) {
			def credsInfo = getAmazonCredentials(zone, clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider as AWSCredentialsProvider
		}
		AmazonEC2ClientBuilder builder = AmazonEC2ClientBuilder.standard().withClientConfiguration(clientConfiguration).withCredentials(credsProvider)

		//def amazonClient = new AmazonEC2Client(credsProvider, clientConfiguration)
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			if(endpoint) {
				AwsClientBuilder.EndpointConfiguration endpointConfig = new AwsClientBuilder.EndpointConfiguration(endpoint,getAmazonEndpointRegion(endpoint))
				builder.withEndpointConfiguration(endpointConfig)
			} //"ec2.us-west-2.amazonaws.com" //this works for strict endpoints, we need to support endpoints
		}

		AmazonEC2 amazonClient = builder.build()

		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'client',fresh)
		return amazonClient
	}

	static getAmazonClient(AccountIntegration accountIntegration, Cloud cloud, Boolean fresh=false, String region=null) {
		def creds
		def credsProvider
		def clientCacheKey = "accountIntegration:${accountIntegration.id ?: java.util.UUID.randomUUID().toString()}:${region}"
		def clientInfo = getCachedClientInfo(clientCacheKey,'client')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		def builder = AmazonEC2ClientBuilder.standard()
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		def clientExpires
		region = region ?: getAmazonEndpoint(cloud ?: accountIntegration)
		region = getAmazonEndpointRegion(region)
		def authConfig = [:]
		if(cloud) {
			clientConfiguration = getClientConfiguration(cloud)
			authConfig.accessKey = accountIntegration.credentialData?.username ?: accountIntegration.serviceUsername ?: getAmazonAccessKey(cloud)
			authConfig.secretKey = accountIntegration.credentialData?.password ?: accountIntegration.servicePassword ?: getAmazonSecretKey(cloud)
			authConfig.useHostCredentials = getAmazonUseHostCredentials(cloud)
			authConfig.stsAssumeRole = cloud.getConfigProperty('stsAssumeRole')
			// authConfig.endpoint =  getAmazonCostingEndpoint(cloud)
			// authConfig.region = getAmazonEndpointRegion(authConfig.endpoint)
		} else {
			authConfig.accessKey = accountIntegration.credentialData?.username ?: accountIntegration.serviceUsername
			authConfig.secretKey = accountIntegration.credentialData?.password ?: accountIntegration.servicePassword
			// authConfig.region = region
			// if(proxySettings) {
			// 	authConfig.apiProxy = proxySettings
			// }
			clientConfiguration = getClientConfiguration(authConfig)
			//global proxy? should we use it on a standalone
		}
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration)
		builder.withRegion(region)
		def amazonClient = builder.build()
		setCachedClientInfo(clientCacheKey,amazonClient,creds,credsProvider,clientExpires,'client',fresh)
		return amazonClient
	}

	static getAmazonRdsClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'rdsClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}

		AmazonRDSClientBuilder builder = AmazonRDSClientBuilder.standard().withClientConfiguration(clientConfiguration).withCredentials(credsProvider)

		//def amazonClient = new AmazonEC2Client(credsProvider, clientConfiguration)
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			if(endpoint) {
				endpoint = endpoint.replaceAll('ec2', 'rds') //rds.us-east-1.amazonaws.com
				AwsClientBuilder.EndpointConfiguration endpointConfig = new AwsClientBuilder.EndpointConfiguration(endpoint,getAmazonEndpointRegion(endpoint))
				builder.withEndpointConfiguration(endpointConfig)
			} //"ec2.us-west-2.amazonaws.com" //this works for strict endpoints, we need to support endpoints
		}
		def amazonClient = builder.build()
		def endpoint = getAmazonEndpoint(zone)

		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'rdsClient',fresh)
		return amazonClient
	}

	static ClientConfiguration getClientConfiguration(Cloud zone) {
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		String proxyHost = zone.apiProxy?.proxyHost
		Integer proxyPort = zone.apiProxy?.proxyPort
		String proxyUser = zone.apiProxy?.proxyUser
		String proxyPassword = zone.apiProxy?.proxyPassword
		String proxyDomain = zone.apiProxy?.proxyDomain
		String proxyWorkstation = zone.apiProxy?.proxyWorkstation
		if(proxyHost)
			clientConfiguration.setProxyHost(proxyHost)
		if(proxyPort)
			clientConfiguration.setProxyPort(proxyPort)
		if(proxyUser)
			clientConfiguration.setProxyUsername(proxyUser)
		if(proxyPassword)
			clientConfiguration.setProxyPassword(proxyPassword)
		if(proxyDomain)
			clientConfiguration.setProxyDomain(proxyDomain)
		if(proxyWorkstation)
			clientConfiguration.setProxyWorkstation(proxyWorkstation)

		clientConfiguration.withThrottledRetries(true)
		//RetryPolicy retryPolicy = new com.amazonaws.retry.PredefinedBackoffStrategies.FullJitterBackoffStrategy(10000, 600000)
		RetryPolicy retryPolicy =  new RetryPolicy.RetryPolicyBuilder().withRetryMode(RetryMode.ADAPTIVE)
				.withBackoffStrategy(new com.amazonaws.retry.PredefinedBackoffStrategies.FullJitterBackoffStrategy(10000, 600000))
		        .withMaxErrorRetry(35)
				.build()
		clientConfiguration.withRetryPolicy(retryPolicy)
		return clientConfiguration
	}

	static ClientConfiguration getClientConfiguration(Map authConfig) {
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		String proxyHost = authConfig.apiProxy?.proxyHost
		Integer proxyPort = authConfig.apiProxy?.proxyPort
		String proxyUser = authConfig.apiProxy?.proxyUser
		String proxyPassword = authConfig.apiProxy?.proxyPassword
		String proxyDomain = authConfig.apiProxy?.proxyDomain
		String proxyWorkstation = authConfig.apiProxy?.proxyWorkstation
		if(proxyHost)
			clientConfiguration.setProxyHost(proxyHost)
		if(proxyPort)
			clientConfiguration.setProxyPort(proxyPort)
		if(proxyUser)
			clientConfiguration.setProxyUsername(proxyUser)
		if(proxyPassword)
			clientConfiguration.setProxyPassword(proxyPassword)
		if(proxyDomain)
			clientConfiguration.setProxyDomain(proxyDomain)
		if(proxyWorkstation)
			clientConfiguration.setProxyWorkstation(proxyWorkstation)
		clientConfiguration.withThrottledRetries(true)
		def retryPolicy = com.amazonaws.retry.PredefinedRetryPolicies.getDefaultRetryPolicyWithCustomMaxRetries(30)
		clientConfiguration.withRetryPolicy(retryPolicy)
		return clientConfiguration
	}

	static Thread clientReaperThread

	static reapExpiredAmazonClients() {
		synchronized(clientLock) {
			for(cacheKey in clients.keySet()) {
				if(clients[cacheKey]?.clientExpires && clients[cacheKey]?.clientExpires < new Date(new Date().time - (10l*60l*1000l))) {
					clients[cacheKey].each { clientKey, client ->
						if(clientKey != 'clientExpires') {
							try {
								client.shutdown()
							} catch(ex2) {
								log.warn("Shutdown Failed of Cached AWS Client {}",ex2.message)
								//we try to shut it down on a reap
							}
						}
					}
					clients.remove(cacheKey)
				}
			}
		}
	}

	static getCachedClientInfo(String cacheKey, String clientName) {
		def rtn = [:]
		synchronized(clientLock) {
			if(!clientReaperThread) {
				clientReaperThread = new Thread().start {
					while(true) {
						try {
							sleep(60000L)
							reapExpiredAmazonClients()
						} catch(t2) {
							// log.warn("Error Running Amazon Reaper Thread {}",t2.message,t2)
						}
					}
				}
			}
			if(clients[cacheKey]) {
				if(clients[cacheKey].clientExpires == null || clients[cacheKey].clientExpires > new Date(new Date().time + (10l*60l*1000l))) {
					if(clients[cacheKey][clientName]) {
						rtn.client = clients[cacheKey][clientName]
					} else {
						rtn.credentials = clients[cacheKey].credentials
						rtn.credentialsProvider = clients[cacheKey].credentialsProvider
					}
				} else if(clients[cacheKey][clientName]) {
					// clients[cacheKey].each { key,value ->
					// 	if(key != 'credentialsProvider' && key != 'credentials' && key != 'clientExpires') {
							
					// 	}
					// }
					try {
						clients[cacheKey][clientName].shutdown()
					} catch(t3) {
						log.warn("Shutdown Failed of Cached AWS Client")
					}
					
					clients.remove(cacheKey)
				}
			}
		}
		return rtn
	}

	static purgeClient(cacheKey) {
		if(clients[cacheKey]) {
			clients.remove(cacheKey)
		}
	}

	static setCachedClientInfo(String cacheKey,amazonClient,creds,credentialsProvider,clientExpires,propertyName, Boolean fresh = false) {
		synchronized(clientLock) {
			def clientInfo = [(propertyName): amazonClient, credentials: creds]
			if(clientExpires && clients[cacheKey]?.clientExpires != clientExpires) {
				clients.remove(cacheKey) //DUH this needs cleared cause the expiration date changed
				clientInfo.clientExpires = clientExpires
			}
			if(credentialsProvider) {
				clientInfo.credentialsProvider = credentialsProvider
			}
			if(fresh || !clients[cacheKey]) {
				clients[cacheKey] = clientInfo
			} else {
				clients[cacheKey] += clientInfo
			}
			
		}
	}

	static getCurrentUser(Cloud zone) {
		def iamClient = getAmazonIamClient(zone)
		GetUserRequest getUserRequest = new GetUserRequest()
	    //  Submit the request using the getUser method of the iamClient object.
	    def user = iamClient.getUser(getUserRequest).getUser()
	    return user
	    // userArn = iamClient.getUser(getUserRequest).getUser().getArn();
	}

	static getAmazonIamClient(Cloud zone, Boolean fresh = false, String region = null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'iamClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		
		
		def builder = AmazonIdentityManagementClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			def endpoint = getAmazonEndpoint(zone)
			def zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}

		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds, credsProvider,clientExpires,'iamClient',fresh)
		return amazonClient
	}

	static getAmazonCloudWatchClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'cloudwatchClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds || !credsProvider) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		AmazonCloudWatchClientBuilder builder = AmazonCloudWatchClientBuilder.standard()

		if(region) {
			builder.setRegion(region)
		} else {

			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'cloudwatchClient',fresh)
		return amazonClient
	}

	static getAmazonCloudFormationClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'cloudFormationClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AmazonCloudFormationClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}

		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'cloudFormationClient',fresh)
		return amazonClient
	}

	static getAmazonSystemsManagementClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'ssmClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires

		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AWSSimpleSystemsManagementClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'ssmClient',fresh)
		return amazonClient
	}

	static getAmazonAutoScalingClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'autoScaleClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AmazonAutoScalingClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'autoScaleClient',fresh)
		return amazonClient
	}

	static getAmazonRoute53Client(AccountIntegration accountIntegration, Cloud cloud, Boolean fresh = false, Map proxySettings=null, Map opts=[:], String region=null) {
		def creds
		def credsProvider
		def clientCacheKey = "accountIntegration:${accountIntegration.id ?: java.util.UUID.randomUUID().toString()}:${region}"
		def clientInfo = getCachedClientInfo(clientCacheKey,'route53Client')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		def builder = AmazonRoute53ClientBuilder.standard()
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		def clientExpires
		def authConfig = [:]
		region = region ?: getAmazonEndpoint(cloud ?: accountIntegration)
		region = getAmazonEndpointRegion(region)
		if(cloud) {
			clientConfiguration = getClientConfiguration(cloud)
			authConfig.accessKey = accountIntegration.credentialData?.username ?: accountIntegration.serviceUsername ?: getAmazonAccessKey(cloud)
			authConfig.secretKey = accountIntegration.credentialData?.password ?: accountIntegration.servicePassword ?: getAmazonSecretKey(cloud)
			authConfig.useHostCredentials = getAmazonUseHostCredentials(cloud)
			authConfig.stsAssumeRole = cloud.getConfigProperty('stsAssumeRole')
			authConfig.endpoint =  getAmazonCostingEndpoint(cloud)
			authConfig.region = getAmazonEndpointRegion(authConfig.endpoint)
		} else {
			authConfig.accessKey = accountIntegration.credentialData?.username ?: accountIntegration.serviceUsername
			authConfig.secretKey = accountIntegration.credentialData?.password ?: accountIntegration.servicePassword
			authConfig.region = region
			if(proxySettings) {
				authConfig.apiProxy = proxySettings
			}
			clientConfiguration = getClientConfiguration(authConfig)
			//global proxy? should we use it on a standalone
		}
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration)
		builder.withRegion(region)
		def amazonClient = builder.build()
		setCachedClientInfo(clientCacheKey,amazonClient,creds,credsProvider,clientExpires,'route53Client',fresh)
		return amazonClient
	}

	static getAmazonElbClient(zone, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'elbClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		AmazonElasticLoadBalancingClientBuilder builder = AmazonElasticLoadBalancingClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}

		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()

		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'elbClient',fresh)
		return amazonClient
	}

	static getAmazonCertificateClient(zone, Boolean fresh = false) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}",'certificateClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def amazonClient = new AWSCertificateManagerClient(credsProvider,clientConfiguration)
		def endpoint = getAmazonEndpoint(zone)
		if(endpoint) {
			def acmEndpoint = "acm${endpoint.substring(endpoint.indexOf('.'))}"
			amazonClient.setEndpoint(acmEndpoint)
		}
		setCachedClientInfo("zone:${zone.id}",amazonClient,creds,credsProvider,clientExpires,'certificateClient',fresh)
		return amazonClient
	}

	static getAmazonSecurityClient(zone, Boolean fresh = false, String region = null) {
		def rtn = [:]
		def creds
		def clientExpires
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'stsClient')
		if(!fresh && clientInfo.client) {
			return [amazonClient: clientInfo.client, securityProvider: clientInfo.credsProvider]
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AWSSecurityTokenServiceClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo("zone:${zone.id}:${region}", amazonClient, creds, credsProvider, clientExpires, 'stsClient', fresh)
		rtn.amazonClient = amazonClient
		rtn.securityProvider = credsProvider
		return rtn
	}

	static getAmazonElbClassicClient(zone, Boolean fresh = false, String region = null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo("zone:${zone.id}:${region}",'elbClassicClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,false,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder builder = com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClientBuilder.standard()

		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = getAmazonEndpoint(zone)
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withClientConfiguration(clientConfiguration).withCredentials(credsProvider).build()
		setCachedClientInfo("zone:${zone.id}:${region}",amazonClient,creds,credsProvider,clientExpires,'elbClassicClient',fresh)
		return amazonClient
	}

	static getAmazonEksClient(Map authConfig, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientInfo = getCachedClientInfo(authConfig.cacheKey,'eksClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		def clientExpires
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AmazonEKSClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			builder.setRegion(authConfig.region)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo(authConfig.cacheKey,amazonClient,creds,credsProvider,clientExpires,'eksClient',fresh)
		return amazonClient
	}

	static getAmazonIamClient(Map authConfig, Boolean fresh = false, String region=null) {
		def creds
		def credsProvider
		def clientExpires
		def clientInfo = getCachedClientInfo(authConfig.cacheKey + ":${region}",'iamClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration,region)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AmazonIdentityManagementClientBuilder.standard()
		if(region) {
			builder.setRegion(region)
		} else {
			String endpoint = authConfig.endpoint
			String zoneRegion = getAmazonEndpointRegion(endpoint)
			builder.setRegion(zoneRegion)
		}
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).build()
		setCachedClientInfo(authConfig.cacheKey + ":${region}",amazonClient,creds,credsProvider,clientExpires,'iamClient',fresh)
		return amazonClient
	}

	static getAmazonSecurityClient(Map authConfig, Boolean fresh = false) {
		def rtn = authConfig
		def creds
		def clientExpires
		def credsProvider
		def clientInfo = getCachedClientInfo(authConfig.cacheKey,'stsClient')
		if(!fresh && clientInfo.client) {
			return [amazonClient: clientInfo.client, securityProvider: clientInfo.credsProvider]
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AWSSecurityTokenServiceClientBuilder.standard()
		def region = authConfig.region
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(region).build()
		setCachedClientInfo(authConfig.cacheKey, amazonClient, creds, credsProvider, clientExpires, 'stsClient', fresh)
		rtn.amazonClient = amazonClient
		rtn.securityProvider = credsProvider
		return rtn
	}

	static getAmazonElasticsearchClient(Map authConfig, Boolean fresh = false) {
		def rtn
		def creds
		def clientExpires
		def credsProvider
		def clientInfo = getCachedClientInfo(authConfig.cacheKey, 'elasticsearchClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
			credsProvider = clientInfo.credentialsProvider
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig, clientConfiguration)
			creds = credsInfo.credentials
			clientExpires = credsInfo.clientExpires
			credsProvider = credsInfo.credsProvider
		}
		def builder = AWSElasticsearchClientBuilder.standard()
		def region = authConfig.region
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(region).build()
		setCachedClientInfo(authConfig.cacheKey, amazonClient, creds, credsProvider, clientExpires, 'elasticsearchClient', fresh)
		rtn = amazonClient
		return rtn
	}

	static getAmazonSecurityProvider(Map authConfig, Boolean fresh = false) {
		def rtn = authConfig
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		def credsInfo = getAmazonCredentials(authConfig, clientConfiguration)
		rtn.securityProvider = credsInfo.credsProvider
		return rtn
	}

	static AWSSecurityTokenService getAmazonCostingSecurityClient(Cloud zone) {
		def rtn
		def authConfig = [:]
		//access / secret key and region
		authConfig.cacheKey = "zone:${zone.id}"
		authConfig.accessKey = getAmazonCostingAccessKey(zone)
		authConfig.secretKey = getAmazonCostingSecretKey(zone)
		authConfig.endpoint =  getAmazonCostingEndpoint(zone)
		authConfig.region = getAmazonEndpointRegion(authConfig.endpoint)
		authConfig.stsAssumeRole = getAmazonStsAssumeRole(zone)
		authConfig.useHostCredentials = getAmazonUseHostCredentials(zone)
		authConfig.stsAssumeRole = AmazonComputeUtility.getAmazonStsAssumeRole(zone)
		authConfig.useHostCredentials = AmazonComputeUtility.getAmazonUseHostCredentials(zone)
		//proxy
		if(zone.apiProxy) {
			authConfig.apiProxy = [:]
			authConfig.apiProxy.proxyHost = zone.apiProxy.proxyHost
			authConfig.apiProxy.proxyPort = zone.apiProxy.proxyPort
			authConfig.apiProxy.proxyUser = zone.apiProxy.proxyUser
			authConfig.apiProxy.proxyPassword = zone.apiProxy.proxyPassword
			authConfig.apiProxy.proxyDomain = zone.apiProxy.proxyDomain
			authConfig.apiProxy.proxyWorkstation = zone.apiProxy.proxyWorkstation
		}
		rtn = AmazonComputeUtility.getAmazonSecurityClient(authConfig)
		return rtn.amazonClient as AWSSecurityTokenService
	}

	static getAmazonPricingClient(Cloud zone) {
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		//configure the connection
		String proxyHost = zone.apiProxy?.proxyHost
		Integer proxyPort = zone.apiProxy?.proxyPort
		String proxyUser = zone.apiProxy?.proxyUser
		String proxyPassword = zone.apiProxy?.proxyPassword
		String proxyDomain = zone.apiProxy?.proxyDomain
		String proxyWorkstation = zone.apiProxy?.proxyWorkstation
		if(proxyHost)
			clientConfiguration.setProxyHost(proxyHost)
		if(proxyPort)
			clientConfiguration.setProxyPort(proxyPort)
		if(proxyUser)
			clientConfiguration.setProxyUsername(proxyUser)
		if(proxyPassword)
			clientConfiguration.setProxyPassword(proxyPassword)
		if(proxyDomain)
			clientConfiguration.setProxyDomain(proxyDomain)
		if(proxyWorkstation)
			clientConfiguration.setProxyWorkstation(proxyWorkstation)
		//build the client
		def builder = AWSPricingClientBuilder.standard()
		def creds = new AWSStaticCredentialsProvider(new BasicAWSCredentials(getAmazonAccessKey(zone), getAmazonSecretKey(zone)))
		def endpoint = getAmazonEndpoint(zone)
		def region = 'us-east-1' //getAmazonEndpoingRegion(endpoint)
		def amazonClient = builder.withCredentials(creds).withClientConfiguration(clientConfiguration).withRegion(region).build()
		return amazonClient
	}

	static getAmazonOrganizationsClient(Cloud zone) {
		ClientConfiguration clientConfiguration = new ClientConfiguration()
		//configure the connection
		String proxyHost = zone.apiProxy?.proxyHost
		Integer proxyPort = zone.apiProxy?.proxyPort
		String proxyUser = zone.apiProxy?.proxyUser
		String proxyPassword = zone.apiProxy?.proxyPassword
		String proxyDomain = zone.apiProxy?.proxyDomain
		String proxyWorkstation = zone.apiProxy?.proxyWorkstation
		if(proxyHost)
			clientConfiguration.setProxyHost(proxyHost)
		if(proxyPort)
			clientConfiguration.setProxyPort(proxyPort)
		if(proxyUser)
			clientConfiguration.setProxyUsername(proxyUser)
		if(proxyPassword)
			clientConfiguration.setProxyPassword(proxyPassword)
		if(proxyDomain)
			clientConfiguration.setProxyDomain(proxyDomain)
		if(proxyWorkstation)
			clientConfiguration.setProxyWorkstation(proxyWorkstation)
		//build the client
		def builder = AWSOrganizationsClientBuilder.standard()
		def creds = new AWSStaticCredentialsProvider(new BasicAWSCredentials(getAmazonAccessKey(zone), getAmazonSecretKey(zone)))
		// def endpoint = getAmazonEndpoint(zone)
		// def region = getAmazonEndpoingRegion(endpoint)
		def amazonClient = builder.withCredentials(creds).withClientConfiguration(clientConfiguration).build()
		return amazonClient
	}

	static getAmazonOrganizationsCostingClient(Cloud zone, Boolean fresh = false) {
		def creds
		def clientExpires
		def clientInfo = getCachedClientInfo("zone:${zone.id}",'orgCostingClient')
		if(!fresh && clientInfo.client) {
			return clientInfo.client
		} else if(!fresh) {
			creds = clientInfo.credentials
		}
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def credsProvider
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,true)
			creds = credsInfo.credentials
			credsProvider = credsInfo.credsProvider ?: new AWSStaticCredentialsProvider(creds)
			clientExpires = credsInfo.clientExpires
		}
		//build the client
		def builder = AWSOrganizationsClientBuilder.standard()
		def endpoint = getAmazonEndpoint(zone)
		def costingEndpoint = getAmazonCostingEndpoint(zone)
		def region = getAmazonEndpointRegion(endpoint)
		def costingRegion = getAmazonEndpointRegion(costingEndpoint)
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(costingRegion).build()
		return amazonClient
	}

	static AWSCostExplorer getAmazonCostClient(Cloud zone, Boolean fresh = false) {
		def creds
		def clientExpires
		// def clientInfo = getCachedClientInfo("zone:${zone.id}",'costClient')
		// if(!fresh && clientInfo.client) {
		// 	return clientInfo.client
		// } else if(!fresh) {
		// 	creds = clientInfo.credentials
		// }
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def credsProvider
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,true)
			creds = credsInfo.credentials
			credsProvider = credsInfo.credsProvider ?: new AWSStaticCredentialsProvider(creds)
			clientExpires = credsInfo.clientExpires
		}
		//build the client
		def builder = AWSCostExplorerClientBuilder.standard()
		def endpoint = getAmazonEndpoint(zone)
		def costingEndpoint = getAmazonCostingEndpoint(zone)
		def region = getAmazonEndpointRegion(endpoint)
		def costingRegion = getAmazonEndpointRegion(costingEndpoint)
		AWSCostExplorer amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(costingRegion).build()
		return amazonClient
	}

	static getAmazonCostReportClient(Cloud zone, Boolean fresh = false) {
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def credsInfo = getAmazonCredentials(zone,clientConfiguration,true)
		def creds = credsInfo.credentials
		def credsProvider = credsInfo.credsProvider ?: new AWSStaticCredentialsProvider(creds)
		//build the client
		def builder = AWSCostAndUsageReportClient.builder()
		def costingEndpoint = getAmazonCostingReportEndpoint(zone)
		def costingRegion = getAmazonEndpointRegion(costingEndpoint)
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration).withRegion(costingRegion).build()
		return amazonClient
	}

	static getAmazonCostingAccessKey(Cloud zone) {
		def config = zone.getConfigMap()
		if(config?.costingAccessKey)
			return config.costingAccessKey
		return getAmazonAccessKey(zone)
	}

	static getAmazonCostingEndpoint(Cloud zone) {
		def rtn = getAmazonEndpoint(zone)
		rtn = rtn.replace('gov-', '')
		return rtn
	}

	static getAmazonCostingReportEndpoint(Cloud zone) {
		return 'cur.us-east-1.amazonaws.com'
	}

	static getAmazonCostingSecretKey(Cloud zone) {
		def config = zone.getConfigMap()
		if(config?.costingSecretKey)
			return config.costingSecretKey
		return getAmazonSecretKey(zone)
	}


	static getAmazonS3Client(Cloud zone, region = null, Boolean fresh = false) {
		def creds
		def clientExpires
		ClientConfiguration clientConfiguration = getClientConfiguration(zone)
		def credsProvider
		if(!creds) {
			def credsInfo = getAmazonCredentials(zone,clientConfiguration,true,region)
			creds = credsInfo.credentials
			credsProvider = credsInfo.credsProvider ?: new AWSStaticCredentialsProvider(creds)
		}
		//build the client
		def builder = AmazonS3ClientBuilder.standard()
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration)

		if(region != false) {
			amazonClient.withRegion(region ?: getAmazonEndpointRegion(getAmazonEndpoint(zone)))
		}
		amazonClient = amazonClient.build()
		return amazonClient
	}

	static getAmazonS3Client(Map authConfig, region = null, Boolean fresh = false) {
		def creds
		def clientExpires
		ClientConfiguration clientConfiguration = getClientConfiguration(authConfig)
		def credsProvider
		if(!creds) {
			def credsInfo = getAmazonCredentials(authConfig,clientConfiguration)
			creds = credsInfo.credentials
			credsProvider = credsInfo.credsProvider ?: new AWSStaticCredentialsProvider(creds)
		}
		//build the client
		def builder = AmazonS3ClientBuilder.standard()
		def amazonClient = builder.withCredentials(credsProvider).withClientConfiguration(clientConfiguration)

		if(region) {
			amazonClient.withRegion(region)
		}
		amazonClient = amazonClient.build()
		return amazonClient
	}

	static void buildAdditionalTags(Object sourceTagList, LinkedList<Tag> tagList, HashSet<String> tagNames = new HashSet<>()) {
		if(sourceTagList) {
			for(it in sourceTagList){
				def maxNameLength = 128
				def maxValueLength = 256
				def truncatedName = truncateElipsis(it.name, maxNameLength - 3)
				def truncatedValue = truncateElipsis(it.value, maxValueLength - 3)
				if(!tagNames.contains(truncatedName) && truncatedValue) {
					tagNames.add(truncatedName)
					def newTag = new com.amazonaws.services.ec2.model.Tag(truncatedName, truncatedValue)
					tagList.add(newTag)
				}

			}
		}
	}

	static truncateElipsis(String str, Integer max) {
		def rtn = str
		if(str && str.length() > max)
			rtn = str.substring(0, max) + '...'
		return rtn
	}

	static asCloudFormationYaml(Map map) {
		def yaml = new org.yaml.snakeyaml.Yaml(new CloudFormationYamlConstructor())
		return yaml.dump(map)
	}

	static extractDiskDisplayName(name) {
		def rtn = name
		if(rtn) {
			def lastSlash = rtn.lastIndexOf('/')
			if(lastSlash > -1) {
				rtn = rtn.substring(lastSlash + 1)
			}
			if(rtn?.endsWith('1')) {
				rtn = rtn.substring(0, rtn.length() - 1)
			}
		}
		rtn
	}

}
