package org.raml.jaxrs.codegen.core;

import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.strip;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.HeaderParam;

import org.apache.commons.lang.StringUtils;
import org.raml.jaxrs.codegen.core.ext.GeneratorExtension;
import org.raml.model.Action;
import org.raml.model.MimeType;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.parameter.AbstractParam;
import org.raml.model.parameter.FormParameter;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.google.common.collect.Maps;
import com.sun.codemodel.JAnnotatable;
import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JDocComment;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;

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
	protected void createResourceInterface(final Resource resource, final Raml raml) throws Exception {
		final String resourceInterfaceName = Names.buildResourceInterfaceName(resource);
        final JDefinedClass resourceInterface = context.createResourceInterface(resourceInterfaceName);
        context.setCurrentResourceInterface(resourceInterface);
        context.getConfiguration().setEmptyResponseReturnVoid(true);

        final String path = resource.getRelativeUri();
        resourceInterface.annotate(RequestMapping.class).param(DEFAULT_ANNOTATION_PARAMETER,
            StringUtils.defaultIfBlank(path, "/"));

        if (isNotBlank(resource.getDescription()))
        {
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

}
