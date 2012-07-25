package com.rackspace.papi.components.clientauth.openstack.v1_0;

import com.rackspace.auth.openstack.ids.CachableGroupInfo;
import com.rackspace.papi.commons.util.regex.ExtractorResult;
import com.rackspace.papi.commons.util.regex.KeyedRegexExtractor;
import com.rackspace.auth.openstack.ids.CachableUserInfo;
import com.rackspace.auth.openstack.ids.OpenStackAuthenticationService;
import com.rackspace.papi.components.clientauth.UriMatcher;
import com.rackspace.papi.components.clientauth.UserAuthGroupsCache;
import com.rackspace.papi.filter.logic.common.AbstractFilterLogicHandler;

import com.rackspace.papi.auth.AuthModule;
import com.rackspace.papi.commons.util.StringUtilities;
import com.rackspace.papi.commons.util.http.CommonHttpHeader;
import com.rackspace.papi.commons.util.http.HttpStatusCode;
import com.rackspace.papi.commons.util.servlet.http.ReadableHttpServletResponse;
import com.rackspace.papi.components.clientauth.UserAuthTokenCache;
import com.rackspace.papi.components.clientauth.openstack.config.OpenstackAuth;
import com.rackspace.papi.filter.logic.FilterAction;
import com.rackspace.papi.filter.logic.FilterDirector;
import com.rackspace.papi.filter.logic.impl.FilterDirectorImpl;

import java.io.IOException;

import com.sun.jersey.api.client.ClientHandlerException;
import org.slf4j.Logger;

import javax.servlet.http.HttpServletRequest;

/**
 * @author fran
 */
public class OpenStackAuthenticationHandler extends AbstractFilterLogicHandler implements AuthModule {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(OpenStackAuthenticationHandler.class);
    private final OpenStackAuthenticationService authenticationService;
    private boolean delegatable;
    private final String authServiceUri;
    private final KeyedRegexExtractor<Object> keyedRegexExtractor;
    private final UserAuthTokenCache<CachableUserInfo> cache;
    private final UserAuthGroupsCache<CachableGroupInfo> grpCache;
    private final UriMatcher uriMatcher;

    public OpenStackAuthenticationHandler(OpenstackAuth cfg, OpenStackAuthenticationService serviceClient, KeyedRegexExtractor keyedRegexExtractor, UserAuthTokenCache cache, UriMatcher uriMatcher, OpenStackGroupInfoCache grpCache) {
        this.authenticationService = serviceClient;
        this.delegatable = cfg.isDelegatable();
        this.authServiceUri = cfg.getIdentityService().getUri();
        this.keyedRegexExtractor = keyedRegexExtractor;
        this.cache = cache;
        this.grpCache = grpCache;
        this.uriMatcher = uriMatcher;
    }

    @Override
    public String getWWWAuthenticateHeaderContents() {
        return "Keystone uri=" + authServiceUri;
    }

    @Override
    public FilterDirector handleRequest(HttpServletRequest request, ReadableHttpServletResponse response) {
        FilterDirector filterDirector = new FilterDirectorImpl();
        filterDirector.setResponseStatus(HttpStatusCode.UNAUTHORIZED);
        filterDirector.setFilterAction(FilterAction.RETURN);

        String uri = request.getRequestURI();
        LOG.debug("Uri is " + uri);
        if (uriMatcher.isUriOnWhiteList(request.getRequestURI())) {
            filterDirector.setFilterAction(FilterAction.PASS);
            LOG.debug("Uri is on whitelist!  Letting request pass through.");
        } else {
            filterDirector = this.authenticate(request);
        }

        return filterDirector;
    }

    @Override
    public FilterDirector authenticate(HttpServletRequest request) {
        final FilterDirector filterDirector = new FilterDirectorImpl();
        filterDirector.setResponseStatus(HttpStatusCode.UNAUTHORIZED);
        filterDirector.setFilterAction(FilterAction.RETURN);

        final String authToken = request.getHeader(CommonHttpHeader.AUTH_TOKEN.toString());
        final ExtractorResult<Object> account = keyedRegexExtractor.extract(request.getRequestURI());
        CachableUserInfo user = null;

        if ((!StringUtilities.isBlank(authToken) && account != null)) {
            user = checkUserCache(account.getResult(), authToken);

            if (user == null) {
                try {
                    user = authenticationService.validateToken(account.getResult(), authToken);
                    cacheUserInfo(user);
                } catch (ClientHandlerException ex) {
                    LOG.error("Failure communicating with the auth service: " + ex.getMessage(), ex);
                    filterDirector.setResponseStatus(HttpStatusCode.INTERNAL_SERVER_ERROR);
                } catch (Exception ex) {
                    LOG.error("Failure in auth: " + ex.getMessage(), ex);
                    filterDirector.setResponseStatus(HttpStatusCode.INTERNAL_SERVER_ERROR);
                }
            }
        }

        CachableGroupInfo groups = null;
        if (user != null) {
            groups = checkGroupCache(user.getUserId());

            if (groups == null) {
                try {
                    groups = authenticationService.getGroups(user.getUserId());
                    cacheGroupInfo(groups);
                } catch (ClientHandlerException ex) {
                    LOG.error("Failure communicating with the auth service when retrieving groups: " + ex.getMessage(), ex);
                    LOG.error("X-PP-Groups will not be set.");
                } catch (Exception ex) {
                    LOG.error("Failure in auth when retrieving groups: " + ex.getMessage(), ex);
                    LOG.error("X-PP-Groups will not be set.");                    
                }
            }
        }

        final AuthenticationHeaderManager headerManager = new AuthenticationHeaderManager(authToken, user, delegatable, filterDirector, account == null ? "" : account.getResult(), groups, request);
        headerManager.setFilterDirectorValues();

        return filterDirector;
    }

    private CachableUserInfo checkUserCache(String userId, String token) {
        if (cache == null) {
            return null;
        }

        return cache.getUserToken(userId, token);
    }

    private void cacheUserInfo(CachableUserInfo user) {
        if (user == null || cache == null) {
            return;
        }

        try {
            cache.storeToken(user.getTenantId(), user, user.tokenTtl().intValue());
        } catch (IOException ex) {
            LOG.warn("Unable to cache user token information: " + user.getUserId() + " Reason: " + ex.getMessage(), ex);
        }
    }

    private CachableGroupInfo checkGroupCache(String userId) {
        if (cache == null) {
            return null;
        }

        return grpCache.getUserGroup(userId);    
    }

    private void cacheGroupInfo(CachableGroupInfo groups) {
        if (groups == null || grpCache == null) {
            return;
        }

        try {
            grpCache.storeGroups(groups.getUserId(), groups, groups.safeGroupTtl());
        } catch (IOException ex) {
            LOG.warn("Unable to cache user group information: " + groups.getUserId() + " Reason: " + ex.getMessage(), ex);
        }
    }

    @Override
    public FilterDirector handleResponse(HttpServletRequest request, ReadableHttpServletResponse response) {
        return new ResponseHandler(response, getWWWAuthenticateHeaderContents()).handle();
    }
}
