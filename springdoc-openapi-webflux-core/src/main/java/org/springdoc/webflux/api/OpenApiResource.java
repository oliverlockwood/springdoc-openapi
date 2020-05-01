/*
 *
 *  * Copyright 2019-2020 the original author or authors.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *      https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.springdoc.webflux.api;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.PathUtils;
import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.models.OpenAPI;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.api.AbstractOpenApiResource;
import org.springdoc.core.AbstractRequestBuilder;
import org.springdoc.core.GenericResponseBuilder;
import org.springdoc.core.OpenAPIBuilder;
import org.springdoc.core.OperationBuilder;
import org.springdoc.core.SpringDocConfigProperties;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.webflux.annotations.RouterOperation;
import org.springdoc.webflux.annotations.RouterOperations;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.type.StandardMethodMetadata;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.result.condition.PatternsRequestCondition;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.reactive.result.method.RequestMappingInfoHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

import static org.springdoc.core.Constants.API_DOCS_URL;
import static org.springdoc.core.Constants.APPLICATION_OPENAPI_YAML;
import static org.springdoc.core.Constants.DEFAULT_API_DOCS_URL_YAML;
import static org.springdoc.core.Constants.DEFAULT_GROUP_NAME;
import static org.springframework.util.AntPathMatcher.DEFAULT_PATH_SEPARATOR;

@RestController
public class OpenApiResource extends AbstractOpenApiResource {

	private static final Logger LOGGER = LoggerFactory.getLogger(OpenApiResource.class);

	private final RequestMappingInfoHandlerMapping requestMappingHandlerMapping;

	@Autowired
	private List<HandlerMapping> handlerMappings;

	public OpenApiResource(String groupName, OpenAPIBuilder openAPIBuilder, AbstractRequestBuilder requestBuilder,
			GenericResponseBuilder responseBuilder, OperationBuilder operationParser,
			RequestMappingInfoHandlerMapping requestMappingHandlerMapping,
			Optional<List<OperationCustomizer>> operationCustomizers,
			Optional<List<OpenApiCustomiser>> openApiCustomisers, SpringDocConfigProperties springDocConfigProperties) {
		super(groupName, openAPIBuilder, requestBuilder, responseBuilder, operationParser, operationCustomizers, openApiCustomisers, springDocConfigProperties);
		this.requestMappingHandlerMapping = requestMappingHandlerMapping;
	}

	@Autowired
	public OpenApiResource(OpenAPIBuilder openAPIBuilder, AbstractRequestBuilder requestBuilder,
			GenericResponseBuilder responseBuilder, OperationBuilder operationParser,
			RequestMappingInfoHandlerMapping requestMappingHandlerMapping,
			Optional<List<OperationCustomizer>> operationCustomizers,
			Optional<List<OpenApiCustomiser>> openApiCustomisers, SpringDocConfigProperties springDocConfigProperties) {
		super(DEFAULT_GROUP_NAME, openAPIBuilder, requestBuilder, responseBuilder, operationParser, operationCustomizers, openApiCustomisers, springDocConfigProperties);
		this.requestMappingHandlerMapping = requestMappingHandlerMapping;
	}

	@Operation(hidden = true)
	@GetMapping(value = API_DOCS_URL, produces = MediaType.APPLICATION_JSON_VALUE)
	public Mono<String> openapiJson(ServerHttpRequest serverHttpRequest, @Value(API_DOCS_URL) String apiDocsUrl)
			throws JsonProcessingException {
		calculateServerUrl(serverHttpRequest, apiDocsUrl);
		OpenAPI openAPI = this.getOpenApi();
		return Mono.just(Json.mapper().writeValueAsString(openAPI));
	}

	@Operation(hidden = true)
	@GetMapping(value = DEFAULT_API_DOCS_URL_YAML, produces = APPLICATION_OPENAPI_YAML)
	public Mono<String> openapiYaml(ServerHttpRequest serverHttpRequest,
			@Value(DEFAULT_API_DOCS_URL_YAML) String apiDocsUrl) throws JsonProcessingException {
		calculateServerUrl(serverHttpRequest, apiDocsUrl);
		OpenAPI openAPI = this.getOpenApi();
		return Mono.just(Yaml.mapper().writeValueAsString(openAPI));
	}

	@Override
	protected void getPaths(Map<String, Object> restControllers) {
		Map<RequestMappingInfo, HandlerMethod> map = requestMappingHandlerMapping.getHandlerMethods();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : map.entrySet()) {
			RequestMappingInfo requestMappingInfo = entry.getKey();
			HandlerMethod handlerMethod = entry.getValue();
			PatternsRequestCondition patternsRequestCondition = requestMappingInfo.getPatternsCondition();
			Set<PathPattern> patterns = patternsRequestCondition.getPatterns();
			for (PathPattern pathPattern : patterns) {
				String operationPath = pathPattern.getPatternString();
				Map<String, String> regexMap = new LinkedHashMap<>();
				operationPath = PathUtils.parsePath(operationPath, regexMap);
				if (operationPath.startsWith(DEFAULT_PATH_SEPARATOR)
						&& restControllers.containsKey(handlerMethod.getBean().toString()) && isPackageToScan(handlerMethod.getBeanType().getPackage().getName()) && isPathToMatch(operationPath)) {
					Set<RequestMethod> requestMethods = requestMappingInfo.getMethodsCondition().getMethods();
					// default allowed requestmethods
					if (requestMethods.isEmpty())
						requestMethods = this.getDefaultAllowedHttpMethods();
					calculatePath(handlerMethod, operationPath, requestMethods);
				}
			}
		}

		ApplicationContext applicationContext = (ApplicationContext) requestMappingHandlerMapping.getApplicationContext();
		Map<String, RouterFunction> routerBeans = applicationContext.getBeansOfType(RouterFunction.class);

		for (Map.Entry<String, RouterFunction> entry : routerBeans.entrySet()) {
			List<RouterOperation> routerOperationList = new ArrayList<>();
			RouterOperations routerOperations = applicationContext.findAnnotationOnBean(entry.getKey(), RouterOperations.class);
			if (routerOperations == null) {
				RouterOperation routerOperation = applicationContext.findAnnotationOnBean(entry.getKey(), RouterOperation.class);
				routerOperationList.add(routerOperation);
			}
			else
				routerOperationList.addAll(Arrays.asList(routerOperations.value()));

			if (!CollectionUtils.isEmpty(routerOperationList)) {
				for (RouterOperation routerOperation : routerOperationList) {
					if (!Void.class.equals(routerOperation.beanClass())) {
						Object handlerBean = applicationContext.getBean(routerOperation.beanClass());
						HandlerMethod handlerMethod = null;
						if (StringUtils.isNotBlank(routerOperation.beanMethod())) {
							try {
								if (ArrayUtils.isEmpty(routerOperation.parameterTypes())) {
									Optional<Method> methodOptional = Arrays.stream(handlerBean.getClass().getDeclaredMethods())
											.filter(method1 -> routerOperation.beanMethod().equals(method1.getName()) && method1.getParameters().length == 0)
											.findAny();
									if (!methodOptional.isPresent())
										methodOptional = Arrays.stream(handlerBean.getClass().getDeclaredMethods())
												.filter(method1 -> routerOperation.beanMethod().equals(method1.getName()))
												.findAny();
									if (methodOptional.isPresent())
										handlerMethod = new HandlerMethod(handlerBean, methodOptional.get());
								}
								else
									handlerMethod = new HandlerMethod(handlerBean, routerOperation.beanMethod(), routerOperation.parameterTypes());
							}
							catch (NoSuchMethodException e) {
								LOGGER.error(e.getMessage());
							}
							if (handlerMethod != null)
								calculatePath(handlerMethod, routerOperation.path(), new HashSet<>(Arrays.asList(routerOperation.method())));
						}
					}
				}
			}
		}
	}

	protected void calculateServerUrl(ServerHttpRequest serverHttpRequest, String apiDocsUrl) {
		String requestUrl = decode(serverHttpRequest.getURI().toString());
		String serverBaseUrl = requestUrl.substring(0, requestUrl.length() - apiDocsUrl.length());
		openAPIBuilder.setServerBaseUrl(serverBaseUrl);
	}

	public List<String> getBeansWithAnnotation(ConfigurableListableBeanFactory factory, Class<? extends Annotation> type) {

		List<String> result = new ArrayList<>();

		for (String name : factory.getBeanDefinitionNames()) {
			BeanDefinition bd = factory.getBeanDefinition(name);

			if (bd.getSource() instanceof StandardMethodMetadata) {
				StandardMethodMetadata metadata = (StandardMethodMetadata) bd.getSource();

				Map<String, Object> attributes = metadata.getAnnotationAttributes(type.getName());
				if (null == attributes) {
					continue;
				}
			}
		}

		return result;
	}
}
