/*
 * Copyright (c) 2025 WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.azure.gw.client.util;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpPipeline;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.Context;
import com.azure.resourcemanager.apimanagement.ApiManagementManager;
import com.azure.resourcemanager.apimanagement.models.ApiContract;
import com.azure.resourcemanager.apimanagement.models.ApiVersionSetContract;
import com.azure.resourcemanager.apimanagement.models.ContentFormat;
import com.azure.resourcemanager.apimanagement.models.VersioningScheme;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.azure.gw.client.AzureConstants;
import org.wso2.azure.gw.client.model.ExportEnvelope;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.Environment;
import java.util.UUID;

/**
 * This class contains utility methods to interact with Azure API Gateway.
 */
public class AzureAPIUtil {
    private static final Log log = LogFactory.getLog(AzureAPIUtil.class);

    /**
     * Deploys an API to the Azure API Management Gateway.
     *
     * @param api          The API object containing the details to be deployed.
     * @param manager      The Azure ApiManagementManager instance for managing APIs.
     * @param resourceGroup The Azure resource group where the API will be deployed.
     * @param serviceName  The name of the Azure API Management service.
     * @return A JSON string containing the reference artifact with UUID and path, or null if deployment fails.
     */
    public static String deployRestAPI(API api, ApiManagementManager manager, String resourceGroup,
                                       String serviceName) throws APIManagementException {
        try {
            String openAPI = api.getSwaggerDefinition();

            String endpointConfig = api.getEndpointConfig();
            if (StringUtils.isEmpty(endpointConfig)) {
                throw new APIManagementException("Endpoint configuration is empty for API: " + api.getId());
            }
            JsonObject endpointConfigJson = JsonParser.parseString(endpointConfig).getAsJsonObject();
            JsonObject prodEndpoints = endpointConfigJson.has("production_endpoints") &&
                      endpointConfigJson.get("production_endpoints").isJsonObject()
                    ? endpointConfigJson.getAsJsonObject("production_endpoints")
                    : null;
            String productionEndpoint = (prodEndpoints != null
                    && prodEndpoints.has("url")
                    && !prodEndpoints.get("url").isJsonNull())
                    ? prodEndpoints.get("url").getAsString()
                    : null;
            if (productionEndpoint == null) {
                log.error("Production endpoint URL is null for API: " + api.getId());
                throw new APIManagementException("Production endpoint URL is null for API: " + api.getId());
            }
            productionEndpoint = productionEndpoint.endsWith("/") ?
                    productionEndpoint.substring(0, productionEndpoint.length() - 1) : productionEndpoint;

            String versionSetId = AzureConstants.AZURE_VERSION_SET_ID_PREFIX + api.getId().getApiName();
            ApiVersionSetContract versionSetContract = manager.apiVersionSets().define(versionSetId)
                    .withExistingService(resourceGroup, serviceName).withDisplayName(versionSetId)
                    .withVersioningScheme(VersioningScheme.SEGMENT).create();

            ApiContract apiContract = manager.apis()
                    .define(api.getUuid())
                    .withExistingService(resourceGroup, serviceName)
                    .withDisplayName(api.getId().getApiName())
                    .withPath(getContextWithoutVersion(api.getContext(), api.getId().getVersion()))
                    .withServiceUrl(productionEndpoint)
                    .withValue(openAPI)
                    .withFormat(ContentFormat.OPENAPI)
                    .withApiVersionSetId(versionSetContract.id())
                    .withApiVersion(api.getId().getVersion())
                    .create();
            log.info("API deployed successfully to Azure Gateway: " + api.getUuid());

            JsonObject referenceArtifact = new JsonObject();
            referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_UUID, api.getUuid());
            referenceArtifact.addProperty(AzureConstants.AZURE_EXTERNAL_REFERENCE_PATH, api.getContext());
            Gson gson = new Gson();
            return gson.toJson(referenceArtifact);
        } catch (Exception e) {
            log.error("Error while deploying API to Azure Gateway: " + api.getId(), e);
            throw new APIManagementException("Error while deploying API to Azure Gateway: " + api.getId(), e);
        }
    }

    private static String getContextWithoutVersion (String contextWithVersion, String version) {
        if (contextWithVersion == null || version == null) {
            return contextWithVersion;
        }
        return contextWithVersion.replace("/" + version, "");
    }

    /**
     * Deletes a deployed API from the Azure API Management Gateway.
     *
     * @param externalReference The external reference containing the UUID of the API to be deleted.
     * @param manager           The Azure ApiManagementManager instance for managing APIs.
     * @param resourceGroup     The Azure resource group where the API is deployed.
     * @param serviceName       The name of the Azure API Management service.
     * @return true
     * @throws APIManagementException If there is an error during the deletion process.
     */
    public static boolean deleteDeployment(String externalReference, ApiManagementManager manager, String resourceGroup,
                                           String serviceName) throws APIManagementException {
        JsonObject root = JsonParser.parseString(externalReference).getAsJsonObject();
        String uuid = root.get(AzureConstants.AZURE_EXTERNAL_REFERENCE_UUID).getAsString();
        manager.apis().delete(resourceGroup, serviceName, uuid, "*", true, Context.NONE);
        return true;
    }

    public static String getRestApiDefinition(ApiManagementManager manager, HttpClient httpClient, ApiContract api)
            throws APIManagementException {
        // This method is implemented with raw REST api calls as the sdk is buggy at the moment

        // Export API
        HttpPipeline pipeline = manager.serviceClient().getHttpPipeline();
        String exportUrl = "https://management.azure.com" + api.id() + "?format=" +
                AzureConstants.AZURE_OPENAPI_EXPORT_FORMAT + "&export=true&api-version=" +
                AzureConstants.AZURE_OPENAPI_EXPORT_VERSION;
        HttpRequest exportReq = new HttpRequest(HttpMethod.GET, exportUrl)
                .setHeaders(new HttpHeaders().set("Accept", "application/json"));
        HttpResponse exportRes = pipeline.send(exportReq).block();
        if (exportRes == null) {
            throw new APIManagementException("No response from ARM export endpoint");
        }
        if (exportRes.getStatusCode() / 100 != 2) {
            String errBody = exportRes.getBodyAsString().block();
            throw new APIManagementException(
                    "Export failed: HTTP " + exportRes.getStatusCode() + " body=" + errBody);
        }
        String exportBody = exportRes.getBodyAsString().block();
        if (exportBody == null || exportBody.isEmpty()) {
            throw new APIManagementException("Export returned empty body");
        }

        ObjectMapper objectMapper = new ObjectMapper();
        ExportEnvelope envelope = null;
        try {
            envelope = objectMapper.readValue(exportBody, ExportEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new APIManagementException("Error parsing export response: " + e.getMessage(), e);
        }
        String sasLink = envelope.getLink();

        HttpRequest blobReq = new HttpRequest(HttpMethod.GET, sasLink)
                .setHeaders(new HttpHeaders().set("Accept", "*/*"));
        HttpResponse blobRes = httpClient.send(blobReq).block();
        if (blobRes == null) {
            throw new APIManagementException("No response while downloading OpenAPI from SAS link");
        }
        if (blobRes.getStatusCode() / 100 != 2) {
            String err = blobRes.getBodyAsString().block();
            throw new APIManagementException("Failed to download OpenAPI: HTTP " + blobRes.getStatusCode() + " body="
                    + err);
        }
        String content = blobRes.getBodyAsString().block();
        if (content == null) {
            throw new APIManagementException("Downloaded OpenAPI content was null");
        }

        return content;
    }

    public static API restAPItoAPI(ApiContract apiContract, String apiDefinition, String organization,
                                   Environment environment) {
        APIIdentifier apiIdentifier = new APIIdentifier("admin", apiContract.displayName(),
                apiContract.apiVersion() != null ? apiContract.apiVersion() : "default");

        API api = new API(apiIdentifier);
        api.setDisplayName(apiContract.displayName());
        api.setUuid(UUID.randomUUID().toString());
        api.setDescription(apiContract.description());
        api.setContext("/" + apiContract.path() + "/" + apiIdentifier.getVersion());
        api.setContextTemplate("/" + apiContract.path() + "/{version}");
        api.setOrganization(organization);
        api.setSwaggerDefinition(apiDefinition);
        api.setRevision(false);
        api.setInitiatedFromGateway(true);
        api.setGatewayVendor("external");
        api.setGatewayType(environment.getGatewayType());
        if (apiContract.serviceUrl() != null) {
            api.setEndpointConfig(AzureAPIUtil.buildEndpointConfigJson(
                    apiContract.serviceUrl(), apiContract.serviceUrl(), false));
        }
        return api;
    }

    /**
     * Build endpointConfig JSON using Gson.
     * Both production and sandbox endpoints are included.
     */
    public static String buildEndpointConfigJson(String productionUrl, String sandboxUrl, boolean failOver) {
        JsonObject endpointConfig = new JsonObject();
        endpointConfig.addProperty("endpoint_type", "http");
        endpointConfig.addProperty("failOver", failOver);

        JsonObject prod = new JsonObject();
        prod.addProperty("template_not_supported", false);
        prod.addProperty("url", productionUrl);

        JsonObject sand = new JsonObject();
        sand.addProperty("template_not_supported", false);
        sand.addProperty("url", sandboxUrl);

        endpointConfig.add("production_endpoints", prod);
        endpointConfig.add("sandbox_endpoints", sand);

        return endpointConfig.toString();
    }
}
