package com.influxdb.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.openapitools.codegen.CodegenDiscriminator;
import org.openapitools.codegen.CodegenModel;
import org.openapitools.codegen.CodegenOperation;
import org.openapitools.codegen.CodegenParameter;
import org.openapitools.codegen.CodegenProperty;
import org.openapitools.codegen.InlineModelResolver;
import org.openapitools.codegen.languages.CSharpClientCodegen;
import org.openapitools.codegen.utils.ModelUtils;

public class InfluxCSharpGenerator extends CSharpClientCodegen {

    /**
     * Configures a friendly name for the generator.  This will be used by the generator
     * to select the library with the -g flag.
     *
     * @return the friendly name for the generator
     */
    @Nonnull
    public String getName() {
        return "influx-csharp";
    }

    /**
     * Returns human-friendly help for the generator.  Provide the consumer with help
     * tips, parameters here
     *
     * @return A string value for the help message
     */
    @Nonnull
    public String getHelp() {
        return "Generates a influx-csharp client library.";
    }

    public InfluxCSharpGenerator() {
        super();

        embeddedTemplateDir = templateDir = "csharp";
    }

    @Override
    public void setGlobalOpenAPI(final OpenAPI openAPI) {

        super.setGlobalOpenAPI(openAPI);

        InlineModelResolver inlineModelResolver = new InlineModelResolver();
        inlineModelResolver.flatten(openAPI);

        String[] schemaNames = openAPI.getComponents().getSchemas().keySet().toArray(new String[0]);
        for (int i = 0; i < schemaNames.length; i++) {
            String schemaName = schemaNames[i];
            Schema schema = openAPI.getComponents().getSchemas().get(schemaName);
            if (schema instanceof ComposedSchema) {


                List<Schema> allOf = ((ComposedSchema) schema).getAllOf();
                if (allOf != null) {

                    allOf.forEach(child -> {

                        if (child instanceof ObjectSchema) {

                            inlineModelResolver.flattenProperties(child.getProperties(), schemaName);
                        }
                    });
                }
            }
        }
    }

    @Override
    public CodegenModel fromModel(final String name, final Schema model, final Map<String, Schema> allDefinitions) {

        CodegenModel codegenModel = super.fromModel(name, model, allDefinitions);

        if (model.getProperties() != null) {


            Map properties = model.getProperties();

            properties
                    .forEach((BiConsumer<String, Schema>) (property, propertySchema) -> {

                        Schema schema = propertySchema;

                        //
                        // Reference to List of Object
                        //
                        if (schema instanceof ArraySchema) {
                            String ref = ((ArraySchema) schema).getItems().get$ref();
                            if (ref != null) {
                                String refSchemaName = ModelUtils.getSimpleRef(ref);
                                Schema refSchema = allDefinitions.get(refSchemaName);

                                if (refSchema instanceof ComposedSchema) {
                                    if (((ComposedSchema) refSchema).getOneOf() != null) {
                                        schema = refSchema;
                                    }
                                }
                            }
                        }

                        //
                        // Reference to Object
                        //
                        else if (schema.get$ref() != null) {
                            String refSchemaName = ModelUtils.getSimpleRef(schema.get$ref());
                            Schema refSchema = allDefinitions.get(refSchemaName);

                            if (refSchema instanceof ComposedSchema) {
                                if (((ComposedSchema) refSchema).getOneOf() != null) {
                                    schema = refSchema;
                                }
                            }
                        }

                        final Discriminator apiDiscriminator = schema.getDiscriminator();

                        CodegenProperty codegenProperty = getCodegenProperty(codegenModel, property);
                        if (codegenProperty != null) {
                            String adapterName = name + codegenProperty.nameInCamelCase + "Adapter";
                            TypeAdapter typeAdapter = new TypeAdapter();
                            typeAdapter.classname = adapterName;

                            Map<String, TypeAdapter> adapters = (HashMap<String, TypeAdapter>) codegenModel.vendorExtensions
                                    .getOrDefault("x-type-adapters", new HashMap<String, TypeAdapter>());

                            if (apiDiscriminator != null) {

                                apiDiscriminator.getMapping().forEach((mappingKey, refSchemaName) ->
                                {
                                    typeAdapter.isArray = propertySchema instanceof ArraySchema;
                                    typeAdapter.discriminator = Stream.of(apiDiscriminator.getPropertyName())
                                            .map(v -> "\"" + v + "\"")
                                            .collect(Collectors.joining(", "));
                                    TypeAdapterItem typeAdapterItem = new TypeAdapterItem();
                                    typeAdapterItem.discriminatorValue = Stream.of(mappingKey).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
                                    typeAdapterItem.classname = ModelUtils.getSimpleRef(refSchemaName);
                                    typeAdapter.items.add(typeAdapterItem);
                                });
                            }

                            if (apiDiscriminator == null && schema instanceof ComposedSchema) {

                                for (Schema oneOf : getOneOf(schema, allDefinitions)) {

                                    String refSchemaName;
                                    Schema refSchema;

                                    if (oneOf.get$ref() == null) {
                                        refSchema = oneOf;
                                        refSchemaName = oneOf.getName();
                                    } else {
                                        refSchemaName = ModelUtils.getSimpleRef(oneOf.get$ref());
                                        refSchema = allDefinitions.get(refSchemaName);
                                        if (refSchema instanceof ComposedSchema) {
                                            List<Schema> schemaList = ((ComposedSchema) refSchema).getAllOf().stream()
                                                    .map(it -> getObjectSchemas(it, allDefinitions))
                                                    .flatMap(Collection::stream)
                                                    .filter(it -> it instanceof ObjectSchema).collect(Collectors.toList());
                                            refSchema = schemaList
                                                    .stream()
                                                    .filter(it -> {
                                                        for (Schema ps : (Collection<Schema>) it.getProperties().values()) {
                                                            if (ps.getEnum() != null && ps.getEnum().size() == 1) {
                                                                return true;
                                                            }
                                                        }
                                                        return false;
                                                    })
                                                    .findFirst()
                                                    .orElse(schemaList.get(0));
                                        }
                                    }

                                    String[] keys = getDiscriminatorKeys(schema, refSchema);

                                    String[] discriminator = new String[]{};
                                    String[] discriminatorValue = new String[]{};

                                    for (String key : keys) {
                                        Schema keyScheme = (Schema) refSchema.getProperties().get(key);
                                        if (keyScheme.get$ref() != null) {
                                            keyScheme = allDefinitions.get(ModelUtils.getSimpleRef(keyScheme.get$ref()));
                                        }

                                        if (!(keyScheme instanceof StringSchema)) {
                                            continue;
                                        } else {

                                            if (((StringSchema) keyScheme).getEnum() != null) {
                                                discriminatorValue = ArrayUtils.add(discriminatorValue, ((StringSchema) keyScheme).getEnum().get(0));
                                            } else {
                                                discriminatorValue = ArrayUtils.add(discriminatorValue, refSchemaName);
                                            }
                                        }

                                        discriminator = ArrayUtils.add(discriminator, key);
                                    }

                                    typeAdapter.isArray = propertySchema instanceof ArraySchema;
                                    typeAdapter.discriminator = Stream.of(discriminator).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
                                    TypeAdapterItem typeAdapterItem = new TypeAdapterItem();
                                    typeAdapterItem.discriminatorValue = Stream.of(discriminatorValue).map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
                                    typeAdapterItem.classname = refSchemaName;
                                    typeAdapter.items.add(typeAdapterItem);
                                }
                            }
                            
                            if (!typeAdapter.items.isEmpty()) {

                                codegenProperty.vendorExtensions.put("x-has-type-adapter", Boolean.TRUE);
                                codegenProperty.vendorExtensions.put("x-type-adapter", adapterName);

                                adapters.put(adapterName, typeAdapter);

                                codegenModel.vendorExtensions.put("x-type-adapters", adapters);
                            }
                        }
                    });
        }


        //
        // set default enum value
        //
        codegenModel.getAllVars().stream()
                .filter(property -> property.isEnum)
                .forEach(this::updateCodegenPropertyEnum);

        //
        // Remove parent read only vars
        //
        Set<CodegenProperty> readOnly = codegenModel.getParentVars().stream()
                .filter(property -> property.isReadOnly)
                .collect(Collectors.toSet());
        codegenModel.getParentVars().removeAll(readOnly);
        codegenModel.readWriteVars = codegenModel.readWriteVars.stream().filter(distinctByKey(CodegenProperty::getName)).collect(Collectors.toList());

        //
        // Add generic name, type and config property
        //
//        if (name.equals("TelegrafRequestPlugin")) {
//
//            codegenModel.interfaces.clear();
//            codegenModel.readWriteVars.clear();
//
//            //
//            // Add type
//            //
//            CodegenProperty typeProperty = new CodegenProperty();
//            typeProperty.name = "type";
//            typeProperty.baseName = "type";
//            typeProperty.getter = "getType";
//            typeProperty.setter = "setType";
//            typeProperty.dataType = "String";
//            typeProperty.isEnum = true;
//            typeProperty.set_enum(Arrays.asList("input", "output"));
//
//            final HashMap<String, Object> allowableValues = new HashMap<>();
//
//            List<Map<String, String>> enumVars = new ArrayList<>();
//            for (String value : Arrays.asList("input", "output")) {
//                Map<String, String> enumVar = new HashMap<>();
//                enumVar.put("name", camelize(value));
//                enumVar.put("value", "" + value + "");
//                enumVars.add(enumVar);
//            }
//            allowableValues.put("enumVars", enumVars);
//
//            typeProperty.setAllowableValues(allowableValues);
//            typeProperty.datatypeWithEnum = "TypeEnum";
//            typeProperty.nameInSnakeCase = "TYPE";
//            typeProperty.isReadOnly = false;
//            typeProperty.hasMore = false;
//            typeProperty.hasMoreNonReadOnly = false;
//            postProcessModelProperty(codegenModel, typeProperty);
//            codegenModel.vars.add(typeProperty);
//            codegenModel.readWriteVars.add(typeProperty);
//
//        }

        // use generic precedents
        if (codegenModel.getParent() != null && codegenModel.getParent().endsWith("Base")) {
            codegenModel.setParent(StringUtils.substringBefore(codegenModel.getParent(), "Base"));
            codegenModel.setParentSchema(StringUtils.substringBefore(codegenModel.getParent(), "Base"));
        }

        if (name.endsWith("ViewProperties") && !name.equals("ViewProperties")) {
            codegenModel.setParent("ViewProperties");
            codegenModel.setParentSchema("ViewProperties");
        }


        if (name.endsWith("VariableProperties") && !name.equals("VariableProperties")) {
            codegenModel.setParent("VariableProperties");
            codegenModel.setParentSchema("VariableProperties");
        }

        if (allDefinitions.containsKey(name + "Base")) {
            codegenModel.setParent(name + "Base");
            codegenModel.setParentSchema(name + "Base");
        }

        if (codegenModel.name.endsWith("NotificationRuleBase") && !codegenModel.name.equals("NotificationRuleBase")) {
            codegenModel.setParent("NotificationRule");
            codegenModel.setParentSchema("NotificationRule");
        }

        if (name.equals("ViewProperties") || name.equals("VariableProperties")) {
            codegenModel.setReadWriteVars(new ArrayList<>());
            codegenModel.setRequiredVars(new ArrayList<>());
            codegenModel.hasOnlyReadOnly = true;
            codegenModel.hasRequired = false;
        }

        if (codegenModel.name.equals("CheckBase")) {
            codegenModel.setParent("CheckDiscriminator");
            codegenModel.setParentSchema("CheckDiscriminator");
        }
        if (codegenModel.name.equals("CheckDiscriminator")) {
            codegenModel.setParent("PostCheck");
            codegenModel.setParentSchema("PostCheck");
        }

        if (codegenModel.name.equals("NotificationEndpointBase")) {
            codegenModel.setParent("NotificationEndpointDiscriminator");
            codegenModel.setParentSchema("NotificationEndpointDiscriminator");
        }

        if (codegenModel.name.equals("PostCheck") || codegenModel.name.equals("PostNotificationEndpoint")) {
            codegenModel.setParent(null);
            codegenModel.setParentSchema(null);
        }

        if (codegenModel.name.equals("DeadmanCheck") || codegenModel.name.equals("ThresholdCheck")) {
            codegenModel.setParent("Check");
            codegenModel.setParentSchema("Check");
        }

        if (codegenModel.name.equals("SlackNotificationEndpoint") || codegenModel.name.equals("PagerDutyNotificationEndpoint")
                || codegenModel.name.equals("HTTPNotificationEndpoint")) {
            codegenModel.setParent("NotificationEndpoint");
            codegenModel.setParentSchema("NotificationEndpoint");
        }

        if (codegenModel.name.equals("NotificationEndpointDiscriminator")) {
            codegenModel.setParent("PostNotificationEndpoint");
            codegenModel.setParentSchema("PostNotificationEndpoint");
        }

        if (codegenModel.name.equals("NotificationRuleBase")) {
            codegenModel.setParent("PostNotificationRule");
            codegenModel.setParentSchema("PostNotificationRule");
        }

        if (codegenModel.name.endsWith("Discriminator")) {
            codegenModel.hasOnlyReadOnly = true;
            codegenModel.hasRequired = false;
            codegenModel.readWriteVars.clear();
        }

        if (codegenModel.name.endsWith("Literal")) {
			codegenModel.setParent("Expression");
			codegenModel.setParentSchema("Expression");
        }

        if (codegenModel.name.endsWith("Statement") && !codegenModel.name.equals("Statement")) {
			codegenModel.setParent("Statement");
			codegenModel.setParentSchema("Statement");
        }

        return codegenModel;
    }

    private String[] getDiscriminatorKeys(final Schema schema, final Schema refSchema) {
        List<String> keys = new ArrayList<>();

        if (refSchema.getProperties() == null) {
            keys.add(schema.getDiscriminator().getPropertyName());
        } else {
            refSchema.getProperties().forEach((BiConsumer<String, Schema>) (property, propertySchema) -> {

                if (keys.isEmpty()) {
                    keys.add(property);

                } else if (propertySchema.getEnum() != null && propertySchema.getEnum().size() == 1) {
                    keys.add(property);
                }
            });
        }

        return keys.toArray(new String[0]);
    }

    @Override
    public CodegenOperation fromOperation(final String path, final String httpMethod, final Operation operation, final Map<String, Schema> schemas, final OpenAPI openAPI) {

        CodegenOperation codegenOperation = super.fromOperation(path, httpMethod, operation, schemas, openAPI);

        //
        // Add optional for enum that doesn't have a default value
        //
        codegenOperation.allParams.stream()
                .filter(parameter -> parameter.defaultValue == null && !parameter.required && schemas.containsKey(parameter.dataType))
                .filter(parameter -> {
                    List enums = schemas.get(parameter.dataType).getEnum();
                    return enums != null && !enums.isEmpty();
                })
                .filter(op -> !op.dataType.endsWith("?"))
                .forEach(op -> op.dataType += "?");

        //
        // Use byte array as body parameter
        //
        codegenOperation.allParams.stream()
                .filter(parameter -> parameter.isBodyParam && parameter.dataType.equals("string"))
                .forEach(parameter -> {
                    parameter.isByteArray = true;
                    parameter.dataType = "byte[]";
                });

        //
        // Set base path
        //
        String url;
        if (operation.getServers() != null) {
            url = operation.getServers().get(0).getUrl();
        } else if (openAPI.getPaths().get(path).getServers() != null) {
            url = openAPI.getPaths().get(path).getServers().get(0).getUrl();
        } else {
            url = openAPI.getServers().get(0).getUrl();
        }

        if (!url.equals("/")) {
            codegenOperation.path = url + codegenOperation.path;
        }

        return codegenOperation;
    }

    @Override
    public void processOpts() {

        super.processOpts();

        List<String> accepted = Arrays.asList(
                "ApiResponse.cs", "OpenAPIDateConverter.cs", "ExceptionFactory.cs",
                "Configuration.cs", "ApiException.cs", "IApiAccessor.cs", "ApiClient.cs",
                "IReadableConfiguration.cs", "GlobalConfiguration.cs");

        //
        // We want to use only the JSON.java
        //
        supportingFiles = supportingFiles.stream()
                .filter(supportingFile -> accepted.contains(supportingFile.destinationFilename))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> postProcessAllModels(final Map<String, Object> models) {

        //
        // Remove type selectors
        //
        Map<String, Object> allModels = super.postProcessAllModels(models);
        additionalProperties.remove("parent");

        for (Map.Entry<String, Object> entry : allModels.entrySet()) {

            String modelName = entry.getKey();
            Object modelConfig = entry.getValue();

            CodegenModel pluginModel = getModel((HashMap) modelConfig);

            //
            // Set Telegraf Plugin name and type in constructors
            //
//            if (modelName.startsWith("TelegrafPlugin") && !modelName.endsWith("Request") && !modelName.toLowerCase().contains("config")) {
//
//                CodegenProperty typeProperty = getCodegenProperty(pluginModel, "type");
//                typeProperty.hasMore = false;
//                typeProperty.defaultValue = "TypeEnum." + getEnumDefaultValue(pluginModel, "type");
//
//                CodegenProperty nameProperty = getCodegenProperty(pluginModel, "name");
//                nameProperty.defaultValue = "NameEnum." + getEnumDefaultValue(pluginModel, "name");
//
//                pluginModel.parent = "TelegrafRequestPlugin";
//
//                // Set Name and Type in Constructor
//                ArrayList<Object> constructorItems = new ArrayList<>();
//                constructorItems.add(String.format("setName(%s);", pluginModel.name + ".NameEnum." + getEnumDefaultValue(pluginModel, "name")));
//                constructorItems.add(String.format("setType(%s);", "TelegrafRequestPlugin.TypeEnum." + getEnumDefaultValue(pluginModel, "type")));
//
//                pluginModel.vendorExtensions.put("x-has-constructor-items", Boolean.TRUE);
//                pluginModel.vendorExtensions.put("x-constructor-items", constructorItems);
//
//                pluginModel.vendorExtensions.put("x-has-inner-enums", Boolean.TRUE);
//                pluginModel.vendorExtensions.put("x-inner-enums", Arrays.asList(nameProperty));
//
//                pluginModel.vars.remove(typeProperty);
//                pluginModel.parentVars.add(typeProperty);
//                pluginModel.vars.get(pluginModel.vars.size() - 1).hasMore = false;
//            }
            if (pluginModel.getParent() != null && (pluginModel.getParent().endsWith("Base"))) {
                CodegenModel parentModel = pluginModel.getParentModel();
                pluginModel.setParentVars(parentModel.getReadWriteVars());
                pluginModel.setReadWriteVars(parentModel.getReadWriteVars());
            }

            if (pluginModel.getChildren() != null) {
                pluginModel.children = pluginModel.getChildren().stream().filter(m -> !m.name.endsWith("Base")).collect(Collectors.toList());
            }

            // Normalize discriminator
            if (pluginModel.getDiscriminatorName() != null) {

                CodegenDiscriminator discriminator = pluginModel.getDiscriminator();

                if (pluginModel.name.endsWith("Discriminator")) {
                    CodegenModel baseModel = getModel((HashMap) allModels.get(pluginModel.name.replace("Discriminator", "")));
                    baseModel.setDiscriminator(discriminator);
                    baseModel.hasRequired = true;
                    baseModel.hasOnlyReadOnly = false;
                    pluginModel.setDiscriminator(null);
                }

                discriminator.setPropertyName(initialCaps(discriminator.getPropertyName()));

                for (CodegenModel child : pluginModel.getChildren()) {
                    CodegenDiscriminator.MappedModel mappedModel = discriminator.getMappedModels().stream()
                            .filter(mapped -> child.name.equals(mapped.getModelName()))
                            .findFirst()
                            .orElse(null);
                    if (mappedModel != null) {
                        child.getVendorExtensions().put("x-discriminator-value", mappedModel.getMappingName());
                    }
                }
            }
        }

        return allModels;
    }

    @Override
    public Map<String, Object> postProcessOperationsWithModels(final Map<String, Object> objs,
                                                               final List<Object> allModels) {

        Map<String, Object> operationsWithModels = super.postProcessOperationsWithModels(objs, allModels);

        List<CodegenOperation> operations = (List<CodegenOperation>) ((HashMap) operationsWithModels.get("operations"))
                .get("operation");

        //
        // For operations with more response type generate additional implementation
        //
        List<CodegenOperation> operationToSplit = operations.stream()
                .filter(operation -> operation.produces.size() > 1)
                .collect(Collectors.toList());
        operationToSplit.forEach(operation -> {

            List<String> returnTypes = operation.produces.stream()
                    .filter(produce -> !produce.get("mediaType").equals("application/json"))
                    .map(produce -> {

                        switch (produce.get("mediaType")) {
                            default:
                            case "application/toml":
                            case "application/octet-stream":
                                return "string";
                        }
                    })
                    .distinct()
                    .collect(Collectors.toList());

            returnTypes.forEach(returnType -> {
                CodegenOperation codegenOperation = new CodegenOperation();
                codegenOperation.baseName = operation.baseName + returnType;
                codegenOperation.summary = operation.summary;
                codegenOperation.notes = operation.notes;
                codegenOperation.allParams = operation.allParams;
                codegenOperation.queryParams = operation.queryParams;
                codegenOperation.pathParams = operation.pathParams;
                codegenOperation.httpMethod = operation.httpMethod;
                codegenOperation.path = operation.path;
                codegenOperation.returnType = returnType;
                codegenOperation.operationId = operation.operationId + returnType;
                codegenOperation.produces = new ArrayList<>();
                HashMap<String, String> producesTypes = new HashMap<>();
                producesTypes.put("mediaType", "application/toml");
                codegenOperation.produces.add(producesTypes);


                operations.add(operations.indexOf(operation) + 1, codegenOperation);
            });
        });

        //
        // For basic auth add authorization header
        //
        operations.stream()
                .filter(operation -> operation.hasAuthMethods)
                .forEach(operation -> {

                    operation.authMethods.stream()
                            .filter(security -> security.isBasic)
                            .forEach(security -> {

                                CodegenParameter authorization = new CodegenParameter();
                                authorization.isHeaderParam = true;
                                authorization.isPrimitiveType = true;
                                authorization.isString = true;
                                authorization.baseName = "Authorization";
                                authorization.paramName = "authorization";
                                authorization.dataType = "String";
                                authorization.description = "An auth credential for the Basic scheme";

                                operation.allParams.get(operation.allParams.size() - 1).hasMore = true;
                                operation.allParams.add(authorization);

                                operation.headerParams.get(operation.headerParams.size() - 1).hasMore = true;
                                operation.headerParams.add(authorization);
                            });
                });

        return operationsWithModels;

    }

    @Override
    public String toApiName(String name) {

        if (name.length() == 0) {
            return "DefaultService";
        }

        //
        // Rename "Api" to "Service"
        //
        return initialCaps(name) + "Service";
    }

    @Override
    public String toModelName(final String name) {
        final String modelName = super.toModelName(name);
        if (isCommonName(modelName)) {
            return modelName + "Type";
        }

        if ("PostBucketRequestRetentionRules".equals(modelName)) {
            return "BucketRetentionRules";
        }

        return modelName;
    }

    @Nonnull
    private String getEnumDefaultValue(final CodegenModel model, final String propertyName) {

        String enumValue = getEnumValue(model, propertyName);

        return enumValue.replace("-", "_");
    }

    @Nonnull
    private String getEnumValue(final CodegenModel model, final String propertyName) {

        CodegenProperty codegenProperty = getCodegenProperty(model, propertyName);
        if (codegenProperty == null) {
            throw new IllegalStateException("Model: " + model + " doesn't have a property: " + propertyName);
        }

        return (String) ((HashMap) ((List) codegenProperty.allowableValues.get("enumVars")).get(0)).get("name");
    }

    @Nullable
    private CodegenProperty getCodegenProperty(final CodegenModel model, final String propertyName) {
        return model.vars.stream()
                .filter(property -> property.baseName.equals(propertyName))
                .findFirst().orElse(null);
    }

    @Nonnull
    private CodegenModel getModel(@Nonnull final HashMap modelConfig) {

        HashMap models = (HashMap) ((ArrayList) modelConfig.get("models")).get(0);

        return (CodegenModel) models.get("model");
    }

    private List<Schema> getObjectSchemas(final Schema schema, final Map<String, Schema> allDefinitions) {
        if (schema instanceof ObjectSchema) {
            return Lists.newArrayList(schema);
        } else if (schema instanceof ComposedSchema) {
            List<Schema> allOf = ((ComposedSchema) schema).getAllOf();
            if (allOf != null) {
                return allOf.stream().map(it -> getObjectSchemas(it, allDefinitions))
                        .flatMap(Collection::stream)
                        .collect(Collectors.toList());
            }
        } else if (schema.get$ref() != null) {
            return Lists.newArrayList(allDefinitions.get(ModelUtils.getSimpleRef(schema.get$ref())));
        }
        return Lists.newArrayList();
    }

    private List<Schema> getOneOf(final Schema schema, final Map<String, Schema> allDefinitions) {

        List<Schema> schemas = new ArrayList<>();

        if (schema instanceof ComposedSchema) {

            ComposedSchema composedSchema = (ComposedSchema) schema;
            for (Schema oneOfSchema : composedSchema.getOneOf()) {

                if (oneOfSchema.get$ref() != null) {

                    Schema refSchema = allDefinitions.get(ModelUtils.getSimpleRef(oneOfSchema.get$ref()));
                    if (refSchema instanceof ComposedSchema && ((ComposedSchema) refSchema).getOneOf() != null) {
                        schemas.addAll(((ComposedSchema) refSchema).getOneOf());
                    } else {
                        schemas.add(oneOfSchema);
                    }
                }
            }
        }

        return schemas;
    }

    private boolean isCommonName(final String modelName) {
        //noinspection RedundantCollectionOperation
        return Collections.singleton("Task").contains(modelName);
    }

    public class TypeAdapter {

        public String classname;
        public String discriminator;
        public boolean isArray;
        public List<TypeAdapterItem> items = new ArrayList<>();
    }

    public class TypeAdapterItem {

        public String discriminatorValue;
        public String classname;
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        return t -> seen.add(keyExtractor.apply(t));
    }
}