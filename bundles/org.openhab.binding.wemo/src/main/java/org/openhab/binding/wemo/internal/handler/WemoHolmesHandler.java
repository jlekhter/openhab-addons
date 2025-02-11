/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.wemo.internal.handler;

import static org.openhab.binding.wemo.internal.WemoBindingConstants.*;
import static org.openhab.binding.wemo.internal.WemoUtil.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.wemo.internal.http.WemoHttpCall;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.transport.upnp.UpnpIOService;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * The {@link WemoHolmesHandler} is responsible for handling commands, which are
 * sent to one of the channels and to update their states.
 *
 * @author Hans-Jörg Merk - Initial contribution;
 */
@NonNullByDefault
public class WemoHolmesHandler extends WemoBaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(WemoHolmesHandler.class);

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Collections.singleton(THING_TYPE_PURIFIER);

    private static final int FILTER_LIFE_DAYS = 330;
    private static final int FILTER_LIFE_MINS = FILTER_LIFE_DAYS * 24 * 60;

    private final Object upnpLock = new Object();
    private final Object jobLock = new Object();

    private final Map<String, String> stateMap = Collections.synchronizedMap(new HashMap<>());

    private Map<String, Boolean> subscriptionState = new HashMap<>();

    private @Nullable ScheduledFuture<?> pollingJob;

    public WemoHolmesHandler(Thing thing, UpnpIOService upnpIOService, WemoHttpCall wemoHttpCaller) {
        super(thing, upnpIOService, wemoHttpCaller);

        logger.debug("Creating a WemoHolmesHandler for thing '{}'", getThing().getUID());
    }

    @Override
    public void initialize() {
        Configuration configuration = getConfig();

        if (configuration.get(UDN) != null) {
            logger.debug("Initializing WemoHolmesHandler for UDN '{}'", configuration.get(UDN));
            UpnpIOService localService = service;
            if (localService != null) {
                localService.registerParticipant(this);
            }
            host = getHost();
            pollingJob = scheduler.scheduleWithFixedDelay(this::poll, 0, DEFAULT_REFRESH_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/config-status.error.missing-udn");
            logger.debug("Cannot initalize WemoHolmesHandler. UDN not set.");
        }
    }

    @Override
    public void dispose() {
        logger.debug("WemoHolmesHandler disposed.");

        ScheduledFuture<?> job = this.pollingJob;
        if (job != null && !job.isCancelled()) {
            job.cancel(true);
        }
        this.pollingJob = null;
        removeSubscription();
    }

    private void poll() {
        synchronized (jobLock) {
            if (pollingJob == null) {
                return;
            }
            try {
                logger.debug("Polling job");
                host = getHost();
                // Check if the Wemo device is set in the UPnP service registry
                // If not, set the thing state to ONLINE/CONFIG-PENDING and wait for the next poll
                if (!isUpnpDeviceRegistered()) {
                    logger.debug("UPnP device {} not yet registered", getUDN());
                    updateStatus(ThingStatus.ONLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                            "@text/config-status.pending.device-not-registered [\"" + getUDN() + "\"]");
                    synchronized (upnpLock) {
                        subscriptionState = new HashMap<>();
                    }
                    return;
                }
                updateStatus(ThingStatus.ONLINE);
                updateWemoState();
                addSubscription();
            } catch (Exception e) {
                logger.debug("Exception during poll: {}", e.getMessage(), e);
            }
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String localHost = getHost();
        if (localHost.isEmpty()) {
            logger.error("Failed to send command '{}' for device '{}': IP address missing", command,
                    getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-ip");
            return;
        }
        String wemoURL = getWemoURL(localHost, DEVICEACTION);
        if (wemoURL == null) {
            logger.error("Failed to send command '{}' for device '{}': URL cannot be created", command,
                    getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-url");
            return;
        }
        String attribute = null;
        String value = null;

        if (command instanceof RefreshType) {
            updateWemoState();
        } else if (CHANNEL_PURIFIERMODE.equals(channelUID.getId())) {
            attribute = "Mode";
            String commandString = command.toString();
            switch (commandString) {
                case "OFF":
                    value = "0";
                    break;
                case "LOW":
                    value = "1";
                    break;
                case "MED":
                    value = "2";
                    break;
                case "HIGH":
                    value = "3";
                    break;
                case "AUTO":
                    value = "4";
                    break;
            }
        } else if (CHANNEL_IONIZER.equals(channelUID.getId())) {
            attribute = "Ionizer";
            if (OnOffType.ON.equals(command)) {
                value = "1";
            } else if (OnOffType.OFF.equals(command)) {
                value = "0";
            }
        } else if (CHANNEL_HUMIDIFIERMODE.equals(channelUID.getId())) {
            attribute = "FanMode";
            String commandString = command.toString();
            switch (commandString) {
                case "OFF":
                    value = "0";
                    break;
                case "MIN":
                    value = "1";
                    break;
                case "LOW":
                    value = "2";
                    break;
                case "MED":
                    value = "3";
                    break;
                case "HIGH":
                    value = "4";
                    break;
                case "MAX":
                    value = "5";
                    break;
            }
        } else if (CHANNEL_DESIREDHUMIDITY.equals(channelUID.getId())) {
            attribute = "DesiredHumidity";
            String commandString = command.toString();
            switch (commandString) {
                case "45":
                    value = "0";
                    break;
                case "50":
                    value = "1";
                    break;
                case "55":
                    value = "2";
                    break;
                case "60":
                    value = "3";
                    break;
                case "100":
                    value = "4";
                    break;
            }
        } else if (CHANNEL_HEATERMODE.equals(channelUID.getId())) {
            attribute = "Mode";
            String commandString = command.toString();
            switch (commandString) {
                case "OFF":
                    value = "0";
                    break;
                case "FROSTPROTECT":
                    value = "1";
                    break;
                case "HIGH":
                    value = "2";
                    break;
                case "LOW":
                    value = "3";
                    break;
                case "ECO":
                    value = "4";
                    break;
            }
        } else if (CHANNEL_TARGETTEMP.equals(channelUID.getId())) {
            attribute = "SetTemperature";
            value = command.toString();
        }
        try {
            String soapHeader = "\"urn:Belkin:service:deviceevent:1#SetAttributes\"";
            String content = "<?xml version=\"1.0\"?>"
                    + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
                    + "<s:Body>" + "<u:SetAttributes xmlns:u=\"urn:Belkin:service:deviceevent:1\">"
                    + "<attributeList>&lt;attribute&gt;&lt;name&gt;" + attribute + "&lt;/name&gt;&lt;value&gt;" + value
                    + "&lt;/value&gt;&lt;/attribute&gt;</attributeList>" + "</u:SetAttributes>" + "</s:Body>"
                    + "</s:Envelope>";
            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null && logger.isTraceEnabled()) {
                logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
            }
        } catch (RuntimeException e) {
            logger.debug("Failed to send command '{}' for device '{}':", command, getThing().getUID(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
        updateStatus(ThingStatus.ONLINE);
    }

    @Override
    public void onServiceSubscribed(@Nullable String service, boolean succeeded) {
        if (service != null) {
            logger.debug("WeMo {}: Subscription to service {} {}", getUDN(), service,
                    succeeded ? "succeeded" : "failed");
            subscriptionState.put(service, succeeded);
        }
    }

    @Override
    public void onValueReceived(@Nullable String variable, @Nullable String value, @Nullable String service) {
        logger.debug("Received pair '{}':'{}' (service '{}') for thing '{}'", variable, value, service,
                this.getThing().getUID());

        updateStatus(ThingStatus.ONLINE);
        if (variable != null && value != null) {
            this.stateMap.put(variable, value);
        }
    }

    private synchronized void addSubscription() {
        synchronized (upnpLock) {
            UpnpIOService localService = service;
            if (localService != null) {
                if (localService.isRegistered(this)) {
                    logger.debug("Checking WeMo GENA subscription for '{}'", getThing().getUID());

                    String subscription = BASICEVENT;

                    if (subscriptionState.get(subscription) == null) {
                        logger.debug("Setting up GENA subscription {}: Subscribing to service {}...", getUDN(),
                                subscription);
                        localService.addSubscription(this, subscription, SUBSCRIPTION_DURATION_SECONDS);
                        subscriptionState.put(subscription, true);
                    }
                } else {
                    logger.debug(
                            "Setting up WeMo GENA subscription for '{}' FAILED - service.isRegistered(this) is FALSE",
                            getThing().getUID());
                }
            }
        }
    }

    private synchronized void removeSubscription() {
        synchronized (upnpLock) {
            UpnpIOService localService = service;
            if (localService != null) {
                if (localService.isRegistered(this)) {
                    logger.debug("Removing WeMo GENA subscription for '{}'", getThing().getUID());
                    String subscription = BASICEVENT;

                    if (subscriptionState.get(subscription) != null) {
                        logger.debug("WeMo {}: Unsubscribing from service {}...", getUDN(), subscription);
                        localService.removeSubscription(this, subscription);
                    }
                    subscriptionState.remove(subscription);
                    localService.unregisterParticipant(this);
                }
            }
        }
    }

    /**
     * The {@link updateWemoState} polls the actual state of a WeMo device and
     * calls {@link onValueReceived} to update the statemap and channels..
     *
     */
    protected void updateWemoState() {
        String localHost = getHost();
        if (localHost.isEmpty()) {
            logger.error("Failed to get actual state for device '{}': IP address missing", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-ip");
            return;
        }
        String actionService = DEVICEACTION;
        String wemoURL = getWemoURL(localHost, actionService);
        if (wemoURL == null) {
            logger.error("Failed to get actual state for device '{}': URL cannot be created", getThing().getUID());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/config-status.error.missing-url");
            return;
        }
        try {
            String action = "GetAttributes";
            String soapHeader = "\"urn:Belkin:service:" + actionService + ":1#" + action + "\"";
            String content = createStateRequestContent(action, actionService);
            String wemoCallResponse = wemoHttpCaller.executeCall(wemoURL, soapHeader, content);
            if (wemoCallResponse != null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("wemoCall to URL '{}' for device '{}'", wemoURL, getThing().getUID());
                    logger.trace("wemoCall with soapHeader '{}' for device '{}'", soapHeader, getThing().getUID());
                    logger.trace("wemoCall with content '{}' for device '{}'", content, getThing().getUID());
                    logger.trace("wemoCall with response '{}' for device '{}'", wemoCallResponse, getThing().getUID());
                }

                String stringParser = substringBetween(wemoCallResponse, "<attributeList>", "</attributeList>");

                // Due to Belkins bad response formatting, we need to run this twice.
                stringParser = unescapeXml(stringParser);
                stringParser = unescapeXml(stringParser);

                logger.trace("AirPurifier response '{}' for device '{}' received", stringParser, getThing().getUID());

                stringParser = "<data>" + stringParser + "</data>";

                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                // see
                // https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html
                dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
                dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
                dbf.setXIncludeAware(false);
                dbf.setExpandEntityReferences(false);
                DocumentBuilder db = dbf.newDocumentBuilder();
                InputSource is = new InputSource();
                is.setCharacterStream(new StringReader(stringParser));

                Document doc = db.parse(is);
                NodeList nodes = doc.getElementsByTagName("attribute");

                // iterate the attributes
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element element = (Element) nodes.item(i);

                    NodeList deviceIndex = element.getElementsByTagName("name");
                    Element line = (Element) deviceIndex.item(0);
                    String attributeName = getCharacterDataFromElement(line);
                    logger.trace("attributeName: {}", attributeName);

                    NodeList deviceID = element.getElementsByTagName("value");
                    line = (Element) deviceID.item(0);
                    String attributeValue = getCharacterDataFromElement(line);
                    logger.trace("attributeValue: {}", attributeValue);

                    State newMode = new StringType();
                    switch (attributeName) {
                        case "Mode":
                            if ("purifier".equals(getThing().getThingTypeUID().getId())) {
                                switch (attributeValue) {
                                    case "0":
                                        newMode = new StringType("OFF");
                                        break;
                                    case "1":
                                        newMode = new StringType("LOW");
                                        break;
                                    case "2":
                                        newMode = new StringType("MED");
                                        break;
                                    case "3":
                                        newMode = new StringType("HIGH");
                                        break;
                                    case "4":
                                        newMode = new StringType("AUTO");
                                        break;
                                }
                                updateState(CHANNEL_PURIFIERMODE, newMode);
                            } else {
                                switch (attributeValue) {
                                    case "0":
                                        newMode = new StringType("OFF");
                                        break;
                                    case "1":
                                        newMode = new StringType("FROSTPROTECT");
                                        break;
                                    case "2":
                                        newMode = new StringType("HIGH");
                                        break;
                                    case "3":
                                        newMode = new StringType("LOW");
                                        break;
                                    case "4":
                                        newMode = new StringType("ECO");
                                        break;
                                }
                                updateState(CHANNEL_HEATERMODE, newMode);
                            }
                            break;
                        case "Ionizer":
                            switch (attributeValue) {
                                case "0":
                                    newMode = OnOffType.OFF;
                                    break;
                                case "1":
                                    newMode = OnOffType.ON;
                                    break;
                            }
                            updateState(CHANNEL_IONIZER, newMode);
                            break;
                        case "AirQuality":
                            switch (attributeValue) {
                                case "0":
                                    newMode = new StringType("POOR");
                                    break;
                                case "1":
                                    newMode = new StringType("MODERATE");
                                    break;
                                case "2":
                                    newMode = new StringType("GOOD");
                                    break;
                            }
                            updateState(CHANNEL_AIRQUALITY, newMode);
                            break;
                        case "FilterLife":
                            int filterLife = Integer.valueOf(attributeValue);
                            if ("purifier".equals(getThing().getThingTypeUID().getId())) {
                                filterLife = Math.round((filterLife / FILTER_LIFE_MINS) * 100);
                            } else {
                                filterLife = Math.round((filterLife / 60480) * 100);
                            }
                            updateState(CHANNEL_FILTERLIFE, new PercentType(String.valueOf(filterLife)));
                            break;
                        case "ExpiredFilterTime":
                            switch (attributeValue) {
                                case "0":
                                    newMode = OnOffType.OFF;
                                    break;
                                case "1":
                                    newMode = OnOffType.ON;
                                    break;
                            }
                            updateState(CHANNEL_EXPIREDFILTERTIME, newMode);
                            break;
                        case "FilterPresent":
                            switch (attributeValue) {
                                case "0":
                                    newMode = OnOffType.OFF;
                                    break;
                                case "1":
                                    newMode = OnOffType.ON;
                                    break;
                            }
                            updateState(CHANNEL_FILTERPRESENT, newMode);
                            break;
                        case "FANMode":
                            switch (attributeValue) {
                                case "0":
                                    newMode = new StringType("OFF");
                                    break;
                                case "1":
                                    newMode = new StringType("LOW");
                                    break;
                                case "2":
                                    newMode = new StringType("MED");
                                    break;
                                case "3":
                                    newMode = new StringType("HIGH");
                                    break;
                                case "4":
                                    newMode = new StringType("AUTO");
                                    break;
                            }
                            updateState(CHANNEL_PURIFIERMODE, newMode);
                            break;
                        case "DesiredHumidity":
                            switch (attributeValue) {
                                case "0":
                                    newMode = new PercentType("45");
                                    break;
                                case "1":
                                    newMode = new PercentType("50");
                                    break;
                                case "2":
                                    newMode = new PercentType("55");
                                    break;
                                case "3":
                                    newMode = new PercentType("60");
                                    break;
                                case "4":
                                    newMode = new PercentType("100");
                                    break;
                            }
                            updateState(CHANNEL_DESIREDHUMIDITY, newMode);
                            break;
                        case "CurrentHumidity":
                            newMode = new StringType(attributeValue);
                            updateState(CHANNEL_CURRENTHUMIDITY, newMode);
                            break;
                        case "Temperature":
                            newMode = new StringType(attributeValue);
                            updateState(CHANNEL_CURRENTTEMP, newMode);
                            break;
                        case "SetTemperature":
                            newMode = new StringType(attributeValue);
                            updateState(CHANNEL_TARGETTEMP, newMode);
                            break;
                        case "AutoOffTime":
                            newMode = new StringType(attributeValue);
                            updateState(CHANNEL_AUTOOFFTIME, newMode);
                            break;
                        case "TimeRemaining":
                            newMode = new StringType(attributeValue);
                            updateState(CHANNEL_HEATINGREMAINING, newMode);
                            break;
                    }
                }
            }
        } catch (RuntimeException | ParserConfigurationException | SAXException | IOException e) {
            logger.debug("Failed to get actual state for device '{}':", getThing().getUID(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
        updateStatus(ThingStatus.ONLINE);
    }
}
