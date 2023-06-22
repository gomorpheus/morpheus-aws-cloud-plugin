package com.morpheusdata.aws

import com.morpheusdata.core.providers.DNSProvider
import com.morpheusdata.core.providers.CloudInitializationProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.AccountIntegration
import com.morpheusdata.model.AccountIntegrationType
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.Icon
import com.morpheusdata.model.NetworkDomainRecord
import com.morpheusdata.model.OptionType
import com.morpheusdata.response.ServiceResponse
import groovy.util.logging.Slf4j

@Slf4j
class Route53DnsProvider implements DNSProvider, CloudInitializationProvider {

	final String name = 'Route 53'
	final String code = 'amazonDns'

	MorpheusContext morpheusContext
    Plugin plugin

    Route53DnsProvider(Plugin plugin, MorpheusContext morpheusContext) {
        this.morpheusContext = morpheusContext
        this.plugin = plugin
    }

	/**
	 * Creates a manually allocated DNS Record of the specified record type on the passed {@link NetworkDomainRecord} object.
	 * This is typically called outside of automation and is a manual method for administration purposes.
	 * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
	 * @param networkDomainRecord The domain record that is being requested for creation. All the metadata needed to create teh record
	 *               should exist here.
	 * @param opts any additional options that may be used in the future to configure behavior. Currently unused
	 * @return a ServiceResponse with the success/error state of the create operation as well as the modified record.
	 */
	@Override
	ServiceResponse createRecord(AccountIntegration integration, NetworkDomainRecord networkDomainRecord, Map opts) {
		return null
	}

	/**
	 * Deletes a Zone Record that is specified on the Morpheus side with the target integration endpoint.
	 * This could be any record type within the specified integration and the authoritative zone object should be
	 * associated with the {@link NetworkDomainRecord} parameter.
	 * @param integration The DNS Integration record which contains things like connectivity info to the DNS Provider
	 * @param networkDomainRecord The zone record object to be deleted on the target integration.
	 * @param opts opts any additional options that may be used in the future to configure behavior. Currently unused
	 * @return the ServiceResponse with the success/error of the delete operation.
	 */
	@Override
	ServiceResponse deleteRecord(AccountIntegration integration, NetworkDomainRecord networkDomainRecord, Map opts) {
		return null
	}

	/**
	 * Provide custom configuration options when creating a new {@link AccountIntegration}
	 * @return a List of OptionType
	 */
	@Override
	List<OptionType> getIntegrationOptionTypes() {
		return null
	}

	/**
	 * Returns the DNS Integration logo for display when a user needs to view or add this integration
	 * @return Icon representation of assets stored in the src/assets of the project.
	 * @since 0.12.3
	 */
	@Override
	Icon getIcon() {
		return new Icon(path:"amazon-route53.svg", darkPath: "amazon-route53-dark.svg")
	}

	@Override
	ServiceResponse initializeProvider(Cloud cloud) {
		ServiceResponse rtn = ServiceResponse.prepare()
		try {
			AccountIntegration integration = new AccountIntegration(
				name: cloud.name,
				type: new AccountIntegrationType(code:"amazonDns"),
				serviceUrl: cloud.regionCode
			)
			morpheus.integration.registerCloudIntegration(cloud.id, integration)
			ServiceResponse.success = true
		} catch (Exception e) {
			rtn.success = false
			log.error("initializeProvider error: {}", e, e)
		}

		return rtn
	}

	/**
	 * Periodically called to refresh and sync data coming from the relevant integration. Most integration providers
	 * provide a method like this that is called periodically (typically 5 - 10 minutes). DNS Sync operates on a 10min
	 * cycle by default. Useful for caching DNS Records created outside of Morpheus.
	 * NOTE: This method is unused when paired with a DNS Provider so simply return null
	 * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
	 */
	@Override
	void refresh(AccountIntegration integration) {
		ServiceResponse rtn = ServiceResponse.prepare()
		return rtn
	}

	/**
	 * Validation Method used to validate all inputs applied to the integration of an DNS Provider upon save.
	 * If an input fails validation or authentication information cannot be verified, Error messages should be returned
	 * via a {@link ServiceResponse} object where the key on the error is the field name and the value is the error message.
	 * If the error is a generic authentication error or unknown error, a standard message can also be sent back in the response.
	 * NOTE: This is unused when paired with an IPAMProvider interface
	 * @param integration The Integration Object contains all the saved information regarding configuration of the DNS Provider.
	 * @param opts any custom payload submission options may exist here
	 * @return A response is returned depending on if the inputs are valid or not.
	 */
	@Override
	ServiceResponse verifyAccountIntegration(AccountIntegration integration, Map opts) {
		return null
	}

	/**
	 * Returns the Morpheus Context for interacting with data stored in the Main Morpheus Application
	 *
	 * @return an implementation of the MorpheusContext for running Future based rxJava queries
	 */
	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	/**
	 * Returns the instance of the Plugin class that this provider is loaded from
	 * @return Plugin class contains references to other providers
	 */
	@Override
	Plugin getPlugin() {
		return plugin
	}


}
