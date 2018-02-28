package com.vary.gong;

import com.google.gson.Gson;
import com.thoughtworks.go.plugin.api.GoApplicationAccessor;
import com.thoughtworks.go.plugin.api.GoPlugin;
import com.thoughtworks.go.plugin.api.GoPluginIdentifier;
import com.thoughtworks.go.plugin.api.annotation.Extension;
import com.thoughtworks.go.plugin.api.exceptions.UnhandledRequestTypeException;
import com.thoughtworks.go.plugin.api.logging.Logger;
import com.thoughtworks.go.plugin.api.request.DefaultGoApiRequest;
import com.thoughtworks.go.plugin.api.request.GoApiRequest;
import com.thoughtworks.go.plugin.api.request.GoPluginApiRequest;
import com.thoughtworks.go.plugin.api.response.DefaultGoPluginApiResponse;
import com.thoughtworks.go.plugin.api.response.GoApiResponse;
import com.thoughtworks.go.plugin.api.response.GoPluginApiResponse;
import com.vary.gong.email.*;
import com.vary.gong.go.api.GoServerApi;
import com.vary.gong.go.api.StageStateChange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;

import static com.vary.gong.go.api.GoApiConstants.*;
import static java.util.Arrays.asList;

@Extension
public class GongNotifierPlugin implements GoPlugin {
	private static Logger LOGGER = Logger.getLoggerFor(GongNotifierPlugin.class);

	private static final String PLUGIN_SETTINGS_GET_CONFIGURATION = "go.plugin-settings.get-configuration";
	private static final String PLUGIN_SETTINGS_GET_VIEW = "go.plugin-settings.get-view";
	private static final String PLUGIN_SETTINGS_VALIDATE_CONFIGURATION = "go.plugin-settings.validate-configuration";
	private static final String REQUEST_NOTIFICATIONS_INTERESTED_IN = "notifications-interested-in";
	public static final String REQUEST_STAGE_STATUS = "stage-status";
	public static final String GET_PLUGIN_SETTINGS = "go.processor.plugin-settings.get";
	private static final String PLUGIN_ID = "com.vary.gong";

	private static final String EXTENSION_NAME = "notification";
	private static final List<String> goSupportedVersions = asList("1.0");

	private PluginSettings settings;
	private GoApplicationAccessor goApplicationAccessor;
	private Gson gson = new Gson();
	private List<NotificationListener> listeners = new LinkedList<>();
	private PipelineInfoProvider pipelineInfo;
	private String settingsTemplate;

	@Override
	public void initializeGoApplicationAccessor(GoApplicationAccessor goApplicationAccessor) {
		this.goApplicationAccessor = goApplicationAccessor;
	}

	@Override
	public GoPluginIdentifier pluginIdentifier() {
		return new GoPluginIdentifier(EXTENSION_NAME, goSupportedVersions);
	}

	@Override
	public GoPluginApiResponse handle(GoPluginApiRequest request) throws UnhandledRequestTypeException {
		String requestName = request.requestName();
		switch (requestName) {
			case PLUGIN_SETTINGS_GET_CONFIGURATION:
				return handleGetPluginSettingsConfiguration();
			case PLUGIN_SETTINGS_GET_VIEW:
				return handleGetPluginSettingsView();
			case PLUGIN_SETTINGS_VALIDATE_CONFIGURATION:
				return handleValidatePluginSettingsConfiguration(request);
			case REQUEST_NOTIFICATIONS_INTERESTED_IN:
				return handleNotificationsInterestedIn();
			case REQUEST_STAGE_STATUS:
				return handleStageStatus(request);
		}

		return error(null);
	}

	private GoPluginApiResponse handleGetPluginSettingsConfiguration() {
		return ok(PluginSettings.FIELD_CONFIG);
	}

	private GoPluginApiResponse handleGetPluginSettingsView() {
		if (settingsTemplate == null) {
			try (InputStream is = getClass().getResourceAsStream("/plugin-settings.template.html")) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				int r;
				byte[] buf = new byte[4096];
				while ((r = is.read(buf)) > 0) {
					bos.write(buf, 0, r);
				}
				settingsTemplate = new String(bos.toByteArray(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				LOGGER.error("Could not load settings template: ", e);
			}
		}

		Map<String, Object> response = new HashMap<>();
		response.put("template", settingsTemplate);
		return ok(response);
	}

	private GoPluginApiResponse handleValidatePluginSettingsConfiguration(GoPluginApiRequest goPluginApiRequest) {
		List<ValidationError> errors = new LinkedList<>();
		return ok(errors);
	}

	private GoPluginApiResponse handleNotificationsInterestedIn() {
		Map<String, Object> response = new HashMap<>();
		response.put("notifications", Arrays.asList(REQUEST_STAGE_STATUS));
		return ok(response);
	}

	private void reinit() {
		listeners.clear();

		pipelineInfo = new CachedPipelineInfoProvider(
				new GoServerApi("http://localhost:8153/go")
					.setAdminCredentials(settings.getRestUser(), settings.getRestPassword()));
		EmailSender sender = new JavaxEmailSender(settings.getSmtpHost(), settings.getSmtpPort());

		listeners.add(new DebugNotificationListener());
		listeners.add(new EmailNotificationListener(pipelineInfo, sender));
	}

	private PluginSettings fetchPluginSettings() {
		Map<String, Object> requestMap = new HashMap<>();
		requestMap.put("plugin-id", PLUGIN_ID);
		GoApiResponse response = goApplicationAccessor.submit(request(GET_PLUGIN_SETTINGS, requestMap));
		if (response.responseBody() == null || response.responseBody().trim().isEmpty()) {
			LOGGER.info("Plugin not configured. Using defaults.");
			return new PluginSettings();
		}
		return new Gson().fromJson(response.responseBody(), PluginSettings.class);
	}

	private GoPluginApiResponse handleStageStatus(GoPluginApiRequest request) {
		LOGGER.info("handleStageStatus: " + request.requestBody());
		PluginSettings currentSettings = fetchPluginSettings();
		if (settings == null || !settings.equals(currentSettings)) {
			LOGGER.info("Plugin settings changed.");
			settings = currentSettings;
			reinit();
		}

		final StageStateChange stateChange = gson.fromJson(request.requestBody(), StageStateChange.class);
		String newState = stateChange.getState();
		String oldState = pipelineInfo.getPipelineHistory(stateChange.getPipelineName())
				.flatMap(h -> h.getPreviousStageResult(stateChange.getStageName(), stateChange.getPipelineCounter()))
				.orElse(null);

		final BiConsumer<NotificationListener, StageStateChange> fn;
		switch (newState) {
			case STATUS_BUILDING:
				fn = NotificationListener::handleBuilding;
				break;
			case STATUS_PASSED:
				fn = STATUS_FAILED.equals(oldState)
						? NotificationListener::handleFixed
						: NotificationListener::handlePassed;
				break;
			case STATUS_FAILED:
				fn = STATUS_PASSED.equals(oldState)
						? NotificationListener::handleBroken
						: NotificationListener::handleFailed;
				break;
			case STATUS_CANCELLED:
				fn = NotificationListener::handleCancelled;
				break;
			default:
				LOGGER.warn("Unknown state " + newState + ". Ignoring it.");
				fn = null;
		}

		if (fn != null) {
			listeners.forEach(l -> fn.accept(l, stateChange));
		}

		return successResponse();
	}

	private GoPluginApiResponse ok(Object response) {
		return jsonResponse(200, response);
	}

	private GoPluginApiResponse error(Object response) {
		return jsonResponse(500, response);
	}

	private GoPluginApiResponse jsonResponse(int responseCode, Object response) {
		return new DefaultGoPluginApiResponse(responseCode, new Gson().toJson(response));
	}

	private GoPluginApiResponse successResponse() {
		Map<String, String> response = new HashMap<>();
		response.put("status", "success");
		return ok(response);
	}

	private GoPluginApiResponse failureResponse(String... messages) {
		Map<String, Object> response = new HashMap<>();
		response.put("status", "failure");
		response.put("messages", Arrays.asList(messages));
		return error(response);
	}

	private GoApiRequest request(String api, Object request) {
		DefaultGoApiRequest req = new DefaultGoApiRequest(api, "1.0", pluginIdentifier());
		req.setRequestBody(new Gson().toJson(request));
		return req;
	}

	private class ValidationError {
		String key;
		String message;
		public ValidationError(String key, String message) {
			this.key = key;
			this.message = message;
		}
	}
}
