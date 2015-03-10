package org.raml.jaxrs.codegen.core;

import static com.sun.codemodel.JMod.PUBLIC;
import static com.sun.codemodel.JMod.STATIC;
import static org.apache.commons.lang.StringUtils.capitalize;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.raml.jaxrs.codegen.core.Names.EXAMPLE_PREFIX;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.raml.jaxrs.codegen.core.ext.GeneratorExtension;
import org.raml.model.Action;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.Response;
import org.raml.model.parameter.AbstractParam;
import org.raml.model.parameter.FormParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.google.common.collect.Maps;
import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.codemodel.JTypeVar;
import com.sun.codemodel.JVar;

//import javax.ws.rs.HeaderParam;
//import javax.ws.rs.core.HttpHeaders;
//import javax.ws.rs.core.Response.ResponseBuilder;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang.StringUtils;

public class SpringMVCGenerator extends Generator {

	private static final String PRODUCES_REQUEST_MAPPING_ATTRIBUTE = "produces";
	private static final String DEFAULT_VALUE_REQUEST_PARAM_ATTRIBUTE = "defaultValue";
	private static final String CONSUMES_REQUEST_MAPPING_ATTRIBUTE = "consumes";
	private static final String REQUEST_MAPPING_METHOD_ATTRIBUTE = "method";
	private static Map<String, RequestMethod> _HTTP_METHODS = Maps.newHashMap();

	public SpringMVCGenerator() {

		for (RequestMethod method : RequestMethod.values()) {
			_HTTP_METHODS.put(method.name(), method);
		}
	}

	@Override
	protected void createResourceInterface(final Resource resource,
			final Raml raml) throws Exception {
		final String resourceInterfaceName = Names
				.buildResourceInterfaceName(resource);
		final JDefinedClass resourceInterface = context
				.createResourceInterface(resourceInterfaceName);
		context.setCurrentResourceInterface(resourceInterface);
		context.getConfiguration().setEmptyResponseReturnVoid(true);

		final String path = resource.getRelativeUri();

		resourceInterface.annotate(RestController.class);

		resourceInterface.annotate(RequestMapping.class).param(
				DEFAULT_ANNOTATION_PARAMETER,
				StringUtils.defaultIfBlank(path, "/"));

		if (isNotBlank(resource.getDescription())) {
			resourceInterface.javadoc().add(resource.getDescription());
		}

		addResourceMethods(resource, resourceInterface, path);

		/* call registered extensions */
		for (GeneratorExtension e : extensions) {
			e.onCreateResourceInterface(resourceInterface, resource);
		}
	}

	protected void addParamAnnotation(final String resourceInterfacePath,
			final Action action, final JMethod method) {
		String path = buildMethodPath(resourceInterfacePath, action);
		if (isNotBlank(path)) {
			JAnnotationUse requestMapping = findAnnotation(method,
					RequestMapping.class);
			requestMapping.param(DEFAULT_ANNOTATION_PARAMETER, path);
		}
	}

	protected void addResourceMethod(final JDefinedClass resourceInterface,
			final String resourceInterfacePath, final Action action,
			final MimeType bodyMimeType,
			final boolean addBodyMimeTypeInMethodName,
			final Collection<MimeType> uniqueResponseMimeTypes)
			throws Exception {
		final String methodName = Names.buildResourceMethodName(action,
				addBodyMimeTypeInMethodName ? bodyMimeType : null);

		Configuration configuration = context.getConfiguration();
		String asyncResourceTrait = configuration.getAsyncResourceTrait();
		boolean asyncMethod = isNotBlank(asyncResourceTrait)
				&& action.getIs().contains(asyncResourceTrait);

		final JType resourceMethodReturnType = getResourceMethodReturnType(
				methodName, action, uniqueResponseMimeTypes.isEmpty(),
				asyncMethod, resourceInterface);

		// the actually created unique method name should be needed in the
		// previous method but
		// no way of doing this :(
		final JMethod method = context.createResourceMethod(resourceInterface,
				methodName, resourceMethodReturnType);

		if (configuration.getMethodThrowException() != null) {
			method._throws(configuration.getMethodThrowException());
		}

		JAnnotationUse requestMappingAnnotation = method
				.annotate(RequestMapping.class);

		addHttpMethod(action.getType().toString(), requestMappingAnnotation);

		addConsumesAnnotation(bodyMimeType, method);
		addProducesAnnotation(uniqueResponseMimeTypes, method);

		addParamAnnotation(resourceInterfacePath, action, method);

		final JDocComment javadoc = addBaseJavaDoc(action, method);

		addPathParameters(action, method, javadoc);
		addHeaderParameters(action, method, javadoc);
		addQueryParameters(action, method, javadoc);
		addBodyParameters(bodyMimeType, method, javadoc);
		if (asyncMethod) {
			addAsyncResponseParameter(asyncResourceTrait, method, javadoc);
		}

		/* call registered extensions */
		for (GeneratorExtension e : extensions) {
			e.onAddResourceMethod(method, action, bodyMimeType,
					uniqueResponseMimeTypes);
		}

	}

	private void addHttpMethod(String httpMethod,
			JAnnotationUse requestMappingAnnotation) {
		requestMappingAnnotation.param(REQUEST_MAPPING_METHOD_ATTRIBUTE,
				_HTTP_METHODS.get(httpMethod));
	}

	@Override
	protected void addFormParameters(MimeType bodyMimeType, JMethod method,
			JDocComment javadoc) throws Exception {
		for (final Entry<String, List<FormParameter>> namedFormParameters : bodyMimeType
				.getFormParameters().entrySet()) {
			addParameter(namedFormParameters.getKey(), namedFormParameters
					.getValue().get(0), RequestParam.class, method, javadoc);
		}
	}

	@Override
	protected void addConsumesAnnotation(MimeType bodyMimeType, JMethod method) {
		if (bodyMimeType != null) {
			JAnnotationUse requestMapping = findAnnotation(method,
					RequestMapping.class);
			requestMapping.param(CONSUMES_REQUEST_MAPPING_ATTRIBUTE,
					bodyMimeType.getType());
		}
	}

	@Override
	protected void addProducesAnnotation(
			Collection<MimeType> uniqueResponseMimeTypes, JMethod method) {
		JAnnotationUse requestMapping = findAnnotation(method,
				RequestMapping.class);
		JAnnotationArrayMember produces = requestMapping
				.paramArray(PRODUCES_REQUEST_MAPPING_ATTRIBUTE);
		for (final MimeType responseMimeType : uniqueResponseMimeTypes) {
			produces.param(responseMimeType.getType());
		}
	}

	private JAnnotationUse findAnnotation(JAnnotatable method,
			Class<? extends Annotation> annotationType) {
		for (JAnnotationUse jAnnotationUse : method.annotations()) {
			if (annotationType.getName().equals(
					jAnnotationUse.getAnnotationClass().fullName())) {
				return jAnnotationUse;
			}
		}
		throw new IllegalArgumentException(String.format("Wrong annotation %s",
				annotationType));
	}

	@Override
	protected void addBodyParameters(MimeType bodyMimeType, JMethod method,
			JDocComment javadoc) throws Exception {
		super.addBodyParameters(bodyMimeType, method, javadoc);
	}

	protected void addAllResourcePathParameters(Resource resource,
			final JMethod method, final JDocComment javadoc) throws Exception {
		addAllResourcePathParameters(resource, method, javadoc,
				RequestParam.class);
	}

	protected void addHeaderParameters(final Action action,
			final JMethod method, final JDocComment javadoc) throws Exception {
		addHeaderParameters(action, method, javadoc, RequestHeader.class);
	}

	protected void addQueryParameters(final Action action,
			final JMethod method, final JDocComment javadoc) throws Exception {
		addQueryParameters(action, method, javadoc, RequestParam.class);
	}

	@Override
	protected void addDefaultValue(AbstractParam parameter,
			JVar argumentVariable) {
		if (parameter.getDefaultValue() != null) {
			JAnnotationUse requestParam = findAnnotation(argumentVariable,
					RequestParam.class);
			requestParam.param(DEFAULT_VALUE_REQUEST_PARAM_ATTRIBUTE,
					parameter.getDefaultValue());
		}
	}

	/****************************************************************************************************/

	// PDonoso

	@Override
	protected JType getResourceMethodReturnType(final String methodName,
			final Action action, final boolean returnsVoid,
			final boolean asyncMethod, final JDefinedClass resourceInterface)
			throws Exception {
		if (asyncMethod) {
			// returns void but also generate the response helper object
			createResourceMethodReturnType(methodName, action,
					resourceInterface);
			return types.getGeneratorType(void.class);
		} else if (returnsVoid
				&& context.getConfiguration().isEmptyResponseReturnVoid()) {
			return types.getGeneratorType(void.class);
		} else {
			return createResourceMethodReturnType(methodName, action,
					resourceInterface);
		}
	}

	@Override
	protected void addAsyncResponseParameter(String asyncResourceTrait,
			final JMethod method, final JDocComment javadoc) throws Exception {

		final String argumentName = Names.buildVariableName(asyncResourceTrait);

		final JVar argumentVariable = method.param(
				types.getGeneratorClass("javax.ws.rs.container.AsyncResponse"),
				argumentName);

		argumentVariable.annotate(types
				.getGeneratorClass("javax.ws.rs.container.Suspended"));
		javadoc.addParam(argumentVariable.name()).add(asyncResourceTrait);
	}

	// FIXME Aquí es donde se debe cambiar el generator o sobreescribir
	private JDefinedClass createResourceMethodReturnType(
			final String methodName, final Action action,
			final JDefinedClass resourceInterface) throws Exception {

		JClass ref = context.getGeneratorClass(ResponseEntity.class.getName())
				.narrow(Object.class);
		final JDefinedClass responseClass = resourceInterface._class(
				capitalize(methodName) + "Response")._extends(ref);

		// Constructor 1
		final JMethod responseClassConstructor = responseClass
				.constructor(JMod.PRIVATE);
		responseClassConstructor.param(HttpStatus.class, "status");

		responseClassConstructor.body().invoke("super")
				.arg(JExpr.ref("status"));

		// Constructor 2
		final JMethod responseClassConstructor2 = responseClass
				.constructor(JMod.PRIVATE);
		responseClassConstructor2.param(HttpStatus.class, "status");
		responseClassConstructor2.param(Object.class, "body");

		responseClassConstructor2.body().invoke("super")
				.arg(JExpr.ref("body,status"));

		// Constructor 3
		final JMethod responseClassConstructor3 = responseClass
				.constructor(JMod.PRIVATE);
		responseClassConstructor3.param(HttpStatus.class, "status");
		responseClassConstructor3.param(Object.class, "body");
		JCodeModel codeModel = new JCodeModel();
		JClass MultiValueMapClass = codeModel.ref(MultiValueMap.class).narrow(
				String.class, String.class);
		responseClassConstructor3.param(MultiValueMapClass, "headers");
		responseClassConstructor3.body().invoke("super")
				.arg(JExpr.ref("body,headers,status"));


		for (final Entry<String, Response> statusCodeAndResponse : action
				.getResponses().entrySet()) {

			createResponseBuilderInResourceMethodReturnType(action,
					responseClass, statusCodeAndResponse);

		}

		return responseClass;
	}

	private void createResponseBuilderInResourceMethodReturnType(
			final Action action, final JDefinedClass responseClass,
			final Entry<String, Response> statusCodeAndResponse)
			throws Exception {

		final int statusCode = NumberUtils
				.toInt(statusCodeAndResponse.getKey());
		final Response response = statusCodeAndResponse.getValue();

		if (!response.hasBody()) {
			createResponseEntity(responseClass,
					statusCode, null);
		} else {
			for (final MimeType mimeType : response.getBody().values()) {
				createResponseEntity(responseClass, statusCode, mimeType);
			}
		}
	}

	private void createResponseEntity(final JDefinedClass responseClass,
			final int statusCode, final MimeType mimeType) {
		final String responseBuilderMethodName = Names.buildResponseMethodName(
				statusCode, mimeType);
		JMethod method = responseClass.method(JMod.PUBLIC | JMod.STATIC,
				responseClass, responseBuilderMethodName);
		JBlock body = method.body();
		
		HttpStatus expectedHttpStatus = HttpStatus.valueOf(statusCode);
		// Cogerlo en funcion de statusCode
		JInvocation args = JExpr._new(responseClass).arg(JExpr.direct("HttpStatus.OK"));
		if (mimeType != null) {
			args.arg(method.param(Object.class, "entity"));
		}
		body._return(args);
	}

}
