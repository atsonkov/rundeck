/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.controllers

import com.dtolabs.rundeck.app.api.tokens.CreateToken
import com.dtolabs.rundeck.app.api.tokens.ListTokens
import com.dtolabs.rundeck.app.api.tokens.RemoveExpiredTokens
import com.dtolabs.rundeck.app.api.tokens.Token
import com.dtolabs.rundeck.core.authentication.tokens.AuthTokenType
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import groovy.transform.CompileStatic
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Post
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.enums.ParameterIn
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.parameters.RequestBody
import org.rundeck.app.api.model.ApiErrorResponse
import org.rundeck.app.api.model.LinkListResponse
import org.rundeck.app.api.model.SystemInfoModel
import org.rundeck.app.authorization.AppAuthContextProcessor
import org.rundeck.core.auth.AuthConstants
import com.dtolabs.rundeck.core.extension.ApplicationExtension
import com.sun.management.OperatingSystemMXBean
import grails.web.mapping.LinkGenerator
import org.rundeck.core.auth.AuthConstants
import org.rundeck.core.auth.app.RundeckAccess
import org.rundeck.core.auth.web.RdAuthorizeSystem
import org.rundeck.util.Sizes
import rundeck.AuthToken
import rundeck.services.ConfigurationService

import javax.servlet.http.HttpServletResponse
import java.lang.management.ManagementFactory

import com.dtolabs.rundeck.app.api.ApiVersions
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag

/**
 * Contains utility actions for API access and responses
 */
@Controller()
class ApiController extends ControllerBase{
    def defaultAction = "invalid"
    def quartzScheduler
    def frameworkService
    ConfigurationService configurationService
    LinkGenerator grailsLinkGenerator

    static allowedMethods = [
            info                 : ['GET'],
            apiTokenList         : ['GET'],
            apiTokenCreate       : ['POST'],
            apiTokenRemoveExpired: ['POST'],
            apiTokenGet          : ['GET'],
            apiTokenDelete       : ['DELETE']
    ]
    def info () {
        respond((Object) [
                apiversion: ApiVersions.API_CURRENT_VERSION,
                href: grailsLinkGenerator.link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}", absolute: true)
            ], formats: ['json']
        )
    }
    def invalid(){
        return apiService.
            renderErrorFormat(
                response,
                [
                    code: 'api.error.invalid.request',
                    args: [request.forwardURI],
                    status: HttpServletResponse.SC_NOT_FOUND
                ]
            )
    }
    /**
     * Respond with a 400 error and information about new endpoint location
     * @return
     */
    def endpointMoved() {
        return apiService.renderErrorFormat(
                response,
                [
                        code: 'api.error.endpoint.moved',
                        args: [
                                request.forwardURI,
                                params.moved_to
                        ],
                        status: HttpServletResponse.SC_BAD_REQUEST
                ]
        )
    }

    /**
     * /api/25/metrics/* forwards to /metrics/* path if enabled
     * @param name
     * @return
     */

    @RdAuthorizeSystem(
        value = RundeckAccess.System.AUTH_READ_OR_ANY_ADMIN,
        description = 'Read System Metrics'
    )

    @Get(
        uri= "/metrics/{name}",
        produces = MediaType.APPLICATION_JSON
    )
    @Operation(
        method = "GET",
        summary = "Get Rundeck metrics",
        description = "Return metrics and information",
        parameters = [
            @Parameter(
                name='name',
                in = ParameterIn.PATH,
                description = 'Metric name, or blank to receive list of metrics',
                allowEmptyValue = true,
                required = false,
                schema = @Schema(
                    type='string',
                    allowableValues=['metrics', 'ping', 'threads', 'healthcheck']
                )
            )
        ]
    )
    @ApiResponse(
        responseCode = "200",
        description = "List of metrics available if not specified",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = LinkListResponse)
        )
    )
    @ApiResponse(
        responseCode = "404",
        description = "Error response",
        content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = ApiErrorResponse),
            examples = @ExampleObject('{"error":true,"errorCode":"api.error.code","message":"not ok","apiversion":41}')
        )
    )
    @Tag(name = "system")
    def apiMetrics(String name) {
        if (!apiService.requireVersion(request, response, ApiVersions.V25)) {
            return
        }


        def names = ['metrics', 'ping', 'threads', 'healthcheck']
        def globalEnabled=configurationService.getBoolean("metrics.enabled", true) &&
                          configurationService.getBoolean("metrics.api.enabled", true)
        Map<String,Boolean> enabled = new HashMap<>()
        LinkListResponse links = new LinkListResponse()
        names.each { mname ->
            enabled[mname] = globalEnabled && configurationService.getBoolean("metrics.api.${mname}.enabled", true)
            if (enabled[mname]) {
                links.addLink(
                    mname,
                    grailsLinkGenerator
                        .link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}/metrics/$mname", absolute: true)
                )
            }
        }
        if (!name) {
            //list enabled endpoints
            return respond(links, formats: ['json'])
        }
        if (!enabled[name]) {
            return apiService.renderErrorFormat(
                response,
                [
                    format: 'json',
                    code  : 'api.error.invalid.request',
                    args  : [
                        request.forwardURI,
                    ],
                    status: HttpServletResponse.SC_NOT_FOUND
                ]
            )
        }
        def servletPath = configurationService.getString('metrics.servletUrlPattern', '/metrics/*')
        forward(uri: servletPath.replace('/*', "/$name"))
    }

    /**
     * API endpoints
     */
    /**
     * Return true if grails configuration allows given feature, or '*' features
     * @param name
     * @return
     */
    private boolean featurePresent(def name){
        boolean featureStatus = configurationService.getBoolean("feature.incubator.${name}", false)

        def splat=configurationService.getBoolean("feature.incubator.*", false)
        return splat || featureStatus
    }
    /**
     * Set an incubator feature toggle on or off
     * @param name
     * @param enable
     */
    private void toggleFeature(def name, boolean enable){
        grailsApplication.config.feature?.incubator?.putAt(name, enable)
    }
    /**
     * Feature toggle api endpoint for development mode
     */
    def featureToggle={
        if (!apiService.requireApi(request, response)) {
            return
        }
        def respond={
            render(contentType: 'text/plain', text: featurePresent(params.featureName) ? 'true' : 'false')
        }
        if(params.featureName){
            if(request.method=='GET'){
                respond()
            } else if (request.method=='PUT'){
                toggleFeature(params.featureName, request.inputStream.text=='true')
                respond()
            }else if(request.method=='POST'){
                toggleFeature(params.featureName, true)
                respond()
            }else if(request.method=='DELETE'){
                toggleFeature(params.featureName, false)
                respond()
            }
        }else{
            response.contentType='text/plain'
            response.outputStream.withWriter('UTF-8') { w ->
                grailsApplication.config.feature?.incubator?.each { k, v ->
                    appendOutput(response, "${k}:${v in [true, 'true']}\n")
                }
            }
            flush(response)
        }
    }


    /*
     * Token API endpoints
     */

    /**
     * /api/11/token/$tokenid
     */

    @Get(
        uri= "/token/{tokenid}",
        produces = MediaType.APPLICATION_JSON
    )
    @Operation(
        method = "GET",
        summary = "Get Token Information",
        description = "API Token information",
        parameters = [
            @Parameter(
                name='tokenid',
                in = ParameterIn.PATH,
                description = 'Token ID (UUID)',
                schema = @Schema(
                    type='string',
                    format='uuid'
                )
            )
        ],
        responses=[
            @ApiResponse(
                responseCode = "200",
                description = "Token Information for GET request",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = Token)
                )
            ),
            @ApiResponse(
                responseCode = "404",
                description = "Not Found",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ApiErrorResponse)
                )
            )

        ]
    )
    @Tag(name = "tokens")

    @CompileStatic
    def apiTokenGet(String tokenid) {
        AuthToken oldtoken = validateTokenRequest(tokenid)

        if (!oldtoken) {
            return
        }

        return respond(new Token(oldtoken, true, apiVersion < ApiVersions.V19), [formats: ['xml', 'json']])
    }

    @CompileStatic
    private AuthToken validateTokenRequest(String tokenid){
        if (!apiService.requireApi(request, response)) {
            return null
        }

        // API V18 and earlier require showing token data which is not possible
        // anymore.
        if (!apiService.requireVersion(request, response, ApiVersions.V19)) {
            return null
        }

        requireParam('tokenid')

        UserAndRolesAuthContext authContext = systemAuthContext
        def adminAuth = apiService.hasTokenAdminAuth(authContext)


        //admin: search by token ID
        //user: search for token ID owned by user
        AuthToken oldtoken = adminAuth ?
                             apiService.findTokenId(tokenid) :
                             apiService.findUserTokenId(authContext.username, tokenid)

        if (!apiService.requireExistsFormat(response, oldtoken, ['Token', tokenid])) {
            return null
        }
        return oldtoken
    }

    @Delete(uri= "/token/{tokenid}")
    @Operation(
        method = "DELETE",
        summary = "Delete API Token",
        parameters = [
            @Parameter(
                name='tokenid',
                in = ParameterIn.PATH,
                description = 'Token ID (UUID)',
                schema = @Schema(
                    type='string',
                    format='uuid'
                )
            )
        ],
        responses=[
            @ApiResponse(responseCode = "204", description = "No Content (DELETE successful)"),

            @ApiResponse(
                responseCode = "404",
                description = "Not Found",
                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ApiErrorResponse)
                )
            )
        ]
    )

    @Tag(name = "tokens")
    @CompileStatic
    def apiTokenDelete(String tokenid) {
        AuthToken oldtoken = validateTokenRequest(tokenid)

        if (!oldtoken) {
            return
        }

        apiService.removeToken(oldtoken)
        return render(status: HttpServletResponse.SC_NO_CONTENT)
    }


    @Get(uri= "/tokens/{user}")
    @Operation(
        method = "GET",
        summary = "Get List of API Tokens",
        parameters = [
            @Parameter(
                name='user',
                in = ParameterIn.PATH,
                description = 'username',
                allowEmptyValue = true,
                required = false,
                schema = @Schema(
                    type='string'
                )
            )
        ],
        responses=[
            @ApiResponse(
                responseCode = "200",
                description = "Token List Response",

                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = ListTokens)
                )
            ),

        ]
    )

    @Tag(name = "tokens")
    /**
     * GET /api/11/tokens/$user?
     */
    @CompileStatic
    def apiTokenList(String user) {
        if (!apiService.requireApi(request, response)) {
            return
        }

        // API V18 and earlier require showing token data which is not possible
        // anymore.
        if (!apiService.requireVersion(request, response, ApiVersions.V19)) {
            return
        }

        UserAndRolesAuthContext authContext = systemAuthContext

        def adminAuth = apiService.hasTokenAdminAuth(authContext)


        if (!adminAuth && user && user != authContext.username) {
            return apiService.renderUnauthorized(response, [AuthConstants.ACTION_ADMIN, 'User', user])
        }
        def tokenlist
        if (user) {
            tokenlist = apiService.findUserTokensCreator(user)
        } else if (!adminAuth) {
            tokenlist = apiService.findUserTokensCreator(authContext.username)
        } else {
            tokenlist = AuthToken.list()
        }

        def data = new ListTokens(user, !user, tokenlist.findAll {
            it.type != AuthTokenType.WEBHOOK
        }.collect {
            new Token(it, true, apiVersion < ApiVersions.V19)
        })

        respond(data, [formats: ['xml', 'json']])
    }


    @Post(uri= "/tokens/{user}", processes = MediaType.APPLICATION_JSON)
    @Operation(
        method = "POST",
        summary = "Create API Token",
        description = 'Create API Token for a specific username, or for current user',
        parameters = [
            @Parameter(
                name = 'user',
                in = ParameterIn.PATH,
                description = 'username',
                allowEmptyValue = true,
                required = false,
                schema = @Schema(
                    type = 'string'
                )
            )
        ],
        requestBody = @RequestBody(
            content = @Content(
                mediaType = MediaType.APPLICATION_JSON,
                schema = @Schema(implementation = CreateToken)
            )
        ),
        responses=[
            @ApiResponse(
                responseCode = "201",
                description = "Token Created",

                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = Token)
                )
            ),

        ]
    )

    @Tag(name = "tokens")
    /**
     * POST /api/11/tokens/$user?
     * @return
     */
    def apiTokenCreate(String user) {
        if (!apiService.requireApi(request, response)) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
            //parse input json or xml
        String tokenuser = user ?: authContext.username
        def roles = null
        def tokenDuration = null
        def tokenName = null
        def errors = []
        boolean tokenRolesV19Enabled = request.api_version >= ApiVersions.V19
        boolean tokensV37 = request.api_version >= ApiVersions.V37

        if (tokenRolesV19Enabled || request.getHeader("Content-Type")) {
            def parsed = apiService.parseJsonXmlWith(request, response, [
                    json: { data ->
                        if (!user) {
                            tokenuser = data.user
                            if (!tokenuser) {
                                errors << " json: expected 'user' property"
                            }
                        }
                        if (tokenRolesV19Enabled) {
                            roles = data.roles
                            tokenDuration = data.duration
                            if (!roles) {
                                errors << " json: expected 'roles' property"
                            }
                        }
                        if (tokensV37) {
                            tokenName = data.name
                        }
                    },
                    xml : { xml ->
                        if (!user) {
                            tokenuser = xml.'@user'.text()
                            if (!tokenuser) {
                                errors << " xml: expected 'user' attribute"
                            }
                        }
                        if (tokenRolesV19Enabled) {
                            roles = xml.'@roles'.text()
                            tokenDuration = xml.'@duration'.text()
                            if (!roles) {
                                errors << " xml: expected 'roles' attribute"
                            }
                        }
                        if (tokensV37) {
                            tokenName = xml.'@name'.text()
                        }
                    }
            ]
            )
            if (!parsed) {
                return
            }
            if (errors) {
                return apiService.renderErrorFormat(response, [
                        status: HttpServletResponse.SC_BAD_REQUEST,
                        code  : 'api.error.invalid.request',
                        args  : ["Format was not valid." + errors.join(" ")]
                ]
                )
            }
        }
        if (!tokenRolesV19Enabled) {
            roles = 'api_token_group'
            tokenDuration = null
        }
        Set<String> rolesSet=null
        if (roles instanceof String) {
            rolesSet = AuthToken.parseAuthRoles(roles)
        } else if (roles instanceof Collection) {
            rolesSet = new HashSet(roles)
        }
        if (rolesSet.size() == 1 && rolesSet.contains('*')) {
            rolesSet = null
        }
        AuthToken token

        Integer tokenDurationSeconds = tokenDuration ? Sizes.parseTimeDuration(tokenDuration) : 0
        if (tokenDuration && !Sizes.validTimeDuration(tokenDuration)) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'api.error.parameter.invalid',
                    args  : [tokenDuration, "duration", "Format was not valid"]
            ]
            )
        }
        try {
            token = apiService.generateUserToken(
                    authContext,
                    tokenDurationSeconds ?: null,
                    tokenuser,
                    rolesSet,
                    true,
                    AuthTokenType.USER,
                    tokenName
            )
        } catch (Exception e) {
            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code  : 'api.error.invalid.request',
                    args  : [e.message]
            ]
            )
        }
        response.status = HttpServletResponse.SC_CREATED
        respond(new Token(token, false, apiVersion < ApiVersions.V19), [formats: ['xml', 'json']])
    }

    @Post(uri= "/tokens/{user}/removeExpired", processes = MediaType.APPLICATION_JSON)
    @Operation(
        method = "POST",
        summary = "Remove Expired Tokens",
        description = 'Remove expired tokens for the specified User',
        parameters = [
            @Parameter(
                name = 'user',
                in = ParameterIn.PATH,
                description = 'username, or special value `*`',
                schema = @Schema(
                    type = 'string'
                )
            )
        ],
        responses=[
            @ApiResponse(
                responseCode = "200",
                description = "Remove expired tokens result",

                content = @Content(
                    mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = RemoveExpiredTokens)
                )
            ),

        ]
    )
    @Tag(name = "tokens")
    /**
     * /api/19/tokens/$user/removeExpired
     */
    def apiTokenRemoveExpired(String user) {
        if (!apiService.requireVersion(request, response, ApiVersions.V19)) {
            return
        }
        AuthContext authContext = rundeckAuthContextProcessor.getAuthContextForSubject(session.subject)
        def adminAuth = apiService.hasTokenAdminAuth(authContext)

        if (!apiService.requireParameters(params, response, ['user'])) {
            return
        }
        def alltokens = user == '*'

        if (!adminAuth && alltokens) {
            return apiService.renderUnauthorized(
                    response,
                    [AuthConstants.ACTION_ADMIN, 'API Tokens for ', "All users"]
            )
        }
        if (!adminAuth && user != authContext.username) {
            return apiService.renderUnauthorized(
                    response,
                    [AuthConstants.ACTION_ADMIN, 'API Tokens for user: ', params.user]
            )
        }

        def resultCount = alltokens ?
                apiService.removeAllExpiredTokens() :
                apiService.removeAllExpiredTokens(user)

        respond(
                new RemoveExpiredTokens(count: resultCount, message: "Removed $resultCount expired tokens"),
                [formats: ['json', 'xml']]
        )
    }

    /*
    End of token API endpoints
     */

    /**
     * /api/1/system/info: display stats and info about the server
     */
    @RdAuthorizeSystem(
        value = RundeckAccess.System.AUTH_READ_OR_OPS_ADMIN,
        description = 'Read System Info'
    )
    @Get(uri="/system/info", produces = MediaType.APPLICATION_JSON)
    @Operation(method = "GET", summary = "Get Rundeck server information and stats",
            description = "Display stats and info about the rundeck server"
    )
    @ApiResponse(responseCode = "200", description = "System info response", content = @Content(mediaType = "application/json",
            schema = @Schema(implementation= SystemInfoModel.class)))
    @Tag(name = "system")
    def apiSystemInfo(){
        if (!apiService.requireApi(request, response)) {
            return
        }

        Date nowDate=new Date();
        String nodeName= servletContext.getAttribute("FRAMEWORK_NODE")
        String appVersion= grailsApplication.metadata['info.app.version']
        String sUUID= frameworkService.getServerUUID()
        double load= ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class).getSystemCpuLoad()
        int processorsCount= ManagementFactory.getOperatingSystemMXBean().availableProcessors
        String osName= ManagementFactory.getOperatingSystemMXBean().name
        String osVersion= ManagementFactory.getOperatingSystemMXBean().version
        String osArch= ManagementFactory.getOperatingSystemMXBean().arch
        String javaVendor=System.properties['java.vendor']
        String javaVersion=System.properties['java.version']
        String vmName=ManagementFactory.getRuntimeMXBean().vmName
        String vmVendor=ManagementFactory.getRuntimeMXBean().vmVendor
        String vmVersion=ManagementFactory.getRuntimeMXBean().vmVersion
        long durationTime=ManagementFactory.getRuntimeMXBean().uptime
        Date startupDate = new Date(nowDate.getTime()-durationTime)
        int threadActiveCount=Thread.activeCount()
        boolean executionModeActive=configurationService.executionModeActive

        def metricsJsonUrl = grailsLinkGenerator.link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}/metrics/metrics?pretty=true", absolute: true)
        def metricsThreadDumpUrl = grailsLinkGenerator.link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}/metrics/threads", absolute: true)
        def metricsHealthcheckUrl = grailsLinkGenerator.link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}/metrics/healthcheck", absolute: true)
        def metricsPingUrl = grailsLinkGenerator.link(uri: "/api/${ApiVersions.API_CURRENT_VERSION}/metrics/ping", absolute: true)

        if (request.api_version < ApiVersions.V14 && !(response.format in ['all','xml'])) {
            return apiService.renderErrorXml(response,[
                    status:HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code: 'api.error.item.unsupported-format',
                    args: [response.format]
            ])
        }
        def extMeta = [:]
        ServiceLoader.load(ApplicationExtension).each {
            extMeta[it.name] = it.infoMetadata
        }
        SystemInfoModel systemInfoModel = new SystemInfoModel(
            system:[
                timestamp: [ epoch:nowDate.getTime(), unit:'ms', datetime:g.w3cDateValue(date:nowDate)],
                rundeck: [ version: appVersion,
                           build: grailsApplication.metadata['build.ident'],
                           buildGit:grailsApplication.metadata['build.core.git.description'],
                           node: nodeName, base: servletContext.getAttribute("RDECK_BASE"),
                           apiversion: ApiVersions.API_CURRENT_VERSION,
                           serverUUID:sUUID ],
                executions: [active:executionModeActive, executionMode:executionModeActive?'active':'passive'],
                os: [arch:osArch, name:osName, version:osVersion],
                jvm:[name:vmName, vendor:javaVendor,version:javaVersion,implementationVersion:vmVersion],
                stats: [uptime:
                               [ duration:durationTime, unit: 'ms', since: [epoch: startupDate.getTime(), unit:'ms',
                                                                           datetime:(g.w3cDateValue(date: startupDate))]
                               ],
                       cpu: [loadAverage:[unit:'percent',average:load], processors:(processorsCount)],
                       memory:[unit:'byte', max:(Runtime.getRuntime().maxMemory()),
                               free:(Runtime.getRuntime().freeMemory()), total:(Runtime.getRuntime().totalMemory())],
                       scheduler: [running:quartzScheduler.getCurrentlyExecutingJobs().size(),
                                   threadPoolSize:quartzScheduler.getMetaData().threadPoolSize],
                       threads: [active: threadActiveCount]
                  ],
                metrics: [href:metricsJsonUrl,contentType:'application/json'],
                threadDump: [href:metricsThreadDumpUrl,contentType:'text/plain'],
                healthcheck: [href:metricsHealthcheckUrl,contentType:'application/json'],
                ping: [href:metricsPingUrl, contentType:'text/plain'],
                extended:extMeta?:null
            ]
        )
        withFormat{
            xml{
                return apiService.renderSuccessXml(request,response){
                    delegate.'system'{
                        timestamp(epoch:nowDate.getTime(),unit:'ms'){
                            datetime(g.w3cDateValue(date:nowDate))
                        }
                        rundeck{
                            version(appVersion)
                            build(grailsApplication.metadata['build.ident'])
                            buildGit(grailsApplication.metadata['build.core.git.description'])
                            node(nodeName)
                            base(servletContext.getAttribute("RDECK_BASE"))
                            apiversion(ApiVersions.API_CURRENT_VERSION)
                            serverUUID(sUUID)
                        }
                        executions(active:executionModeActive,executionMode:executionModeActive?'active':'passive')
                        os {
                            arch(osArch)
                            name(osName)
                            version(osVersion)
                        }
                        jvm {
                            name(vmName)
                            vendor(javaVendor)
                            version(javaVersion)
                            implementationVersion(vmVersion)
                        }
                        stats{
                            uptime(duration:durationTime,unit: 'ms'){
                                since(epoch: startupDate.getTime(),unit:'ms'){
                                    datetime(g.w3cDateValue(date: startupDate))
                                }
                            }
                            //                errorCount('12')
                            //                    requestCount('12')
                            cpu{
                                loadAverage(unit:'percent',load)
                                processors(processorsCount)
                            }
                            memory(unit:'byte'){
                                max(Runtime.getRuntime().maxMemory())
                                free(Runtime.getRuntime().freeMemory())
                                total(Runtime.getRuntime().totalMemory())
                            }
                            scheduler{
                                running(quartzScheduler.getCurrentlyExecutingJobs().size())
                                threadPoolSize(quartzScheduler.getMetaData().threadPoolSize)
                            }
                            threads{
                                active(threadActiveCount)
                            }
                        }
                        metrics(href:metricsJsonUrl,contentType:'application/json')
                        threadDump(href:metricsThreadDumpUrl,contentType:'text/plain')
                        healthcheck(href:metricsHealthcheckUrl,contentType:'application/json')
                        ping(href:metricsPingUrl,contentType:'text/plain')

                        if (extMeta) {
                            extended {

                                def dl = delegate
                                extMeta.each { k, v ->
                                    dl."$k"(v)
                                }
                            }

                        }
                    }
                }

            }
            json{

                return apiService.renderSuccessJson(response){
                    systemInfoModel

                }
            }
        }
    }
}
