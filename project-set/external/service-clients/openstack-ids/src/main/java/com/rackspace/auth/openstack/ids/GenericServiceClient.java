package com.rackspace.auth.openstack.ids;

import javax.ws.rs.core.MediaType;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.client.config.DefaultClientConfig;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.api.client.WebResource.Builder;
import com.sun.jersey.api.client.filter.LoggingFilter;
import com.sun.ws.rs.ext.RuntimeDelegateImpl;
import org.openstack.docs.identity.api.v2.AuthenticationRequest;
import org.openstack.docs.identity.api.v2.PasswordCredentialsBase;
import org.openstack.docs.identity.api.v2.PasswordCredentialsRequiredUsername;

import javax.ws.rs.ext.RuntimeDelegate;
import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.util.Map;

/**
 * @author fran
 */
public class GenericServiceClient {
    static {
        // If this works we need to figure out why and make sure it's part of our init
        RuntimeDelegate.setInstance(new RuntimeDelegateImpl());
    }

    private final Client client;
    private final String ACCEPT = "application/xml";

    private Builder setHeaders(Builder builder, Map<String, String> headers) {
        for (String key: headers.keySet()) {
            builder = builder.header(key, headers.get(key));
        }

        return builder;
    }

    public GenericServiceClient(String username, String password) {
        DefaultClientConfig cc = new DefaultClientConfig();
        cc.getProperties().put(ClientConfig.PROPERTY_FOLLOW_REDIRECTS, false);
        client = Client.create(cc);

        HTTPBasicAuthFilter authFilter = new HTTPBasicAuthFilter(username, password);
        client.addFilter(authFilter);

        client.addFilter(new LoggingFilter());
    }

    public ServiceClientResponse getAdminToken(String uri, String username, String password) throws AuthServiceException {
        WebResource resource = client.resource(uri);

        PasswordCredentialsRequiredUsername credentials = new PasswordCredentialsRequiredUsername();
        credentials.setUsername(username);
        credentials.setPassword(password);

        JAXBElement jaxbCredentials = new JAXBElement(new QName("http://docs.openstack.org/identity/api/v2.0","passwordCredentials"), PasswordCredentialsRequiredUsername.class, credentials);
        AuthenticationRequest request = new AuthenticationRequest();
        request.setCredential(jaxbCredentials);

        JAXBElement jaxbRequest = new JAXBElement(new QName("http://docs.openstack.org/identity/api/v2.0", "auth"), AuthenticationRequest.class, request);
        ClientResponse response = resource.type(MediaType.APPLICATION_XML_TYPE).header("Accept", "application/xml").post(ClientResponse.class, jaxbRequest);

        return new ServiceClientResponse(response.getStatus(), response.getEntityInputStream());
    }

    public ServiceClientResponse get(String uri, String adminToken, String... queryParameters) throws AuthServiceException {
        WebResource resource = client.resource(uri);

        if (queryParameters.length % 2 != 0) {
           throw new IllegalArgumentException("Query parameters must be in pairs.");
        }

        for (int index = 0; index < queryParameters.length; index = index + 2) {
           resource = resource.queryParam(queryParameters[index], queryParameters[index + 1]);
        }

        ClientResponse response = resource.header("Accept", "application/xml").header("X-Auth-Token", adminToken).get(ClientResponse.class);
        return new ServiceClientResponse(response.getStatus(), response.getEntityInputStream());
    }
}