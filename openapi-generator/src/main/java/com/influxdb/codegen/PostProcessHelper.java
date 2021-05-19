package com.influxdb.codegen;

import java.util.Map;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.intellij.lang.annotations.Language;
import org.openapitools.codegen.CodegenOperation;

/**
 * @author Jakub Bednar (18/05/2021 13:20)
 */
class PostProcessHelper
{
	private final OpenAPI openAPI;

	public PostProcessHelper(final OpenAPI openAPI)
	{
		this.openAPI = openAPI;
	}

	void postProcessOpenAPI()
	{
		//
		// Drop supports for InfluxQL
		//
		{
			RequestBody requestBody = openAPI.getPaths().get("/query").getPost().getRequestBody();
			MediaType mediaType = requestBody.getContent().get("application/json");
			// Set correct schema to `Query` object
			Schema schema = ((ComposedSchema) mediaType.getSchema()).getOneOf().get(0);
			mediaType.schema(schema);
			dropSchemas("InfluxQLQuery");
		}

		//
		// Set DateTimeSchema for DateTimeLiteral
		//
		{
			ObjectSchema objectSchema = (ObjectSchema) openAPI.getComponents().getSchemas().get("DateTimeLiteral");
			Map<String, Schema> properties = objectSchema.getProperties();
			Schema dateTimeSchema = new DateTimeSchema()
					.type("string")
					.format("date-time")
					.description(properties.get("value").getDescription());
			properties.put("value", dateTimeSchema);
		}

		//
		// Use generic scheme for Telegraf plugins instead of TelegrafInputCPU, TelegrafInputMem, ...
		//
		{
			ObjectSchema objectSchema = (ObjectSchema) openAPI.getComponents().getSchemas().get("TelegrafPlugin");
			Map<String, Schema> properties = objectSchema.getProperties();
			Schema configSchema = new ObjectSchema()
					.additionalProperties(new ObjectSchema())
					.description(properties.get("config").getDescription());
			properties.put("config", configSchema);

			// remove plugins
			dropSchemas("TelegrafPluginInput(.+)|TelegrafPluginOutput(.+)|TelegrafRequestPlugin");
		}

		//
		// Drop supports form Geo
		//
		{
			dropSchemas("Geo(.*)View(.*)");
		}
	}

	void postProcessOperation(String path, Operation operation, CodegenOperation op)
	{
		//
		// Set correct path for /health, /ready, /setup ...
		//
		String url;
		if (operation.getServers() != null) {
			url = operation.getServers().get(0).getUrl();
		} else if (openAPI.getPaths().get(path).getServers() != null) {
			url = openAPI.getPaths().get(path).getServers().get(0).getUrl();
		} else {
			url = openAPI.getServers().get(0).getUrl();
		}

		if (url != null) {
			url = url.replaceAll("https://raw.githubusercontent.com", "");
		}

		if (!"/".equals(url) && url != null) {
			op.path = url + op.path;
		}
	}

	private void dropSchemas(@Language("RegExp") final String regexp)
	{
		openAPI.getComponents()
				.getSchemas()
				.entrySet()
				.removeIf(entry -> entry.getKey().matches(regexp));
	}
}
