package org.openremote.manager.energy;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.camel.builder.RouteBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.persistence.PersistenceEvent;
import org.openremote.container.timer.TimerService;
import org.openremote.container.web.WebTargetBuilder;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.datapoint.AssetPredictedDatapointService;
import org.openremote.manager.event.ClientEventService;
import org.openremote.manager.gateway.GatewayService;
import org.openremote.manager.rules.RulesService;
import org.openremote.model.Container;
import org.openremote.model.ContainerService;
import org.openremote.model.asset.Asset;
import org.openremote.model.asset.impl.ElectricityProducerSolarAsset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.geo.GeoJSONPoint;
import org.openremote.model.query.AssetQuery;

import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
import static java.time.format.DateTimeFormatter.ISO_LOCAL_TIME;
import static org.openremote.container.persistence.PersistenceEvent.PERSISTENCE_TOPIC;
import static org.openremote.container.persistence.PersistenceEvent.isPersistenceEventForEntityType;
import static org.openremote.container.util.MapAccess.getString;
import static org.openremote.manager.gateway.GatewayService.isNotForGateway;

/**
 * Fills in power forecast from ForecastSolar (https://forecast.solar) for {@link ElectricityProducerSolarAsset}.
 */
public class ForecastSolarService extends RouteBuilder implements ContainerService {

    protected static class EstimateResponse {
        @JsonProperty
        protected Result result;
    }

    protected static  class  Result{
        @JsonProperty
        protected Map<String, Double> wattHours;
        @JsonProperty
        protected Map<String, Double> wattHoursDay;
        @JsonProperty
        protected Map<String, Double> watts;
    }
    
    public static final String FORECAST_SOLAR_API_KEY = "FORECAST_SOLAR_API_KEY";

    protected static final DateTimeFormatter ISO_LOCAL_DATE_TIME_WITHOUT_T = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .append(ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .append(ISO_LOCAL_TIME)
            .toFormatter();

    protected ScheduledExecutorService executorService;
    protected AssetStorageService assetStorageService;
    protected AssetProcessingService assetProcessingService;
    protected AssetPredictedDatapointService assetPredictedDatapointService;
    protected GatewayService gatewayService;
    protected ClientEventService clientEventService;
    protected RulesService rulesService;
    protected TimerService timerService;

    protected static final Logger LOG = Logger.getLogger(ForecastSolarService.class.getName());

    protected ResteasyWebTarget forecastSolarClient;
    private String forecastSolarApiKey;

    private final Map<String, ScheduledFuture<?>> calculationFutures = new HashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public void configure() throws Exception {
        from(PERSISTENCE_TOPIC)
                .routeId("ForecastSolarAssetPersistenceChanges")
                .filter(isPersistenceEventForEntityType(ElectricityProducerSolarAsset.class))
                .filter(isNotForGateway(gatewayService))
                .process(exchange -> processAssetChange((PersistenceEvent<ElectricityProducerSolarAsset>) exchange.getIn().getBody(PersistenceEvent.class)));
    }

    @Override
    public void init(Container container) throws Exception {
        assetStorageService = container.getService(AssetStorageService.class);
        assetProcessingService = container.getService(AssetProcessingService.class);
        gatewayService = container.getService(GatewayService.class);
        assetPredictedDatapointService = container.getService(AssetPredictedDatapointService.class);
        clientEventService = container.getService(ClientEventService.class);
        executorService = container.getExecutorService();
        rulesService = container.getService(RulesService.class);
        timerService = container.getService(TimerService.class);

        forecastSolarApiKey = getString(container.getConfig(), FORECAST_SOLAR_API_KEY, null);
    }

    @Override
    public void start(Container container) throws Exception {
        if (forecastSolarApiKey == null) {
            LOG.info("No value found for FORECAST_SOLAR_API_KEY, ForecastSolarService won't start");
            return;
        }

        forecastSolarClient = WebTargetBuilder.createClient(executorService)
                .target("https://api.forecast.solar/" + forecastSolarApiKey + "/estimate");

        container.getService(MessageBrokerService.class).getContext().addRoutes(this);

        // Load all enabled producer solar assets
        LOG.fine("Loading producer solar assets...");

        List<ElectricityProducerSolarAsset> electricityProducerSolarAssets = assetStorageService.findAll(
                        new AssetQuery()
                                .select(new AssetQuery.Select().excludeParentInfo(true))
                                .types(ElectricityProducerSolarAsset.class)
                )
                .stream()
                .map(asset -> (ElectricityProducerSolarAsset) asset)
                .filter(electricityProducerSolarAsset -> electricityProducerSolarAsset.isIncludeForecastSolarService().orElse(false))
                .collect(Collectors.toList());

        LOG.fine("Found includes producer solar asset count = " + electricityProducerSolarAssets.size());

        electricityProducerSolarAssets.forEach(this::startProcessing);

        clientEventService.addInternalSubscription(
                AttributeEvent.class,
                null,
                this::processAttributeEvent);
    }

    @Override
    public void stop(Container container) throws Exception {
        calculationFutures.keySet().forEach(this::stopProcessing);
    }

    protected void processAttributeEvent(AttributeEvent attributeEvent) {
        ScheduledFuture<?> calculationFuture = calculationFutures.get(attributeEvent.getAssetId());

        if (calculationFuture != null) {
            processElectricityProducerSolarAssetAttributeEvent(attributeEvent);
        }
    }

    protected synchronized void processElectricityProducerSolarAssetAttributeEvent(AttributeEvent attributeEvent) {

        if (ElectricityProducerSolarAsset.POWER.getName().equals(attributeEvent.getAttributeName())
                || ElectricityProducerSolarAsset.POWER_FORECAST.getName().equals(attributeEvent.getAttributeName())) {
            // These are updated by this service
            return;
        }

        if (attributeEvent.getAttributeName().equals(ElectricityProducerSolarAsset.INCLUDE_FORECAST_SOLAR_SERVICE.getName())) {
            boolean disabled = attributeEvent.<Boolean>getValue().orElse(false);
            if (!disabled && calculationFutures.containsKey(attributeEvent.getAssetId())) {
                // Nothing to do here
                return;
            } else if (disabled && !calculationFutures.containsKey(attributeEvent.getAssetId())) {
                // Nothing to do here
                return;
            }
        }

        LOG.info("Processing producer solar asset attribute event: " + attributeEvent);
        stopProcessing(attributeEvent.getAssetId());

        // Get latest asset from storage
        ElectricityProducerSolarAsset asset = (ElectricityProducerSolarAsset) assetStorageService.find(attributeEvent.getAssetId());

        if (asset != null && asset.isIncludeForecastSolarService().orElse(false)) {
            startProcessing(asset);
        }
    }

    protected void processAssetChange(PersistenceEvent<ElectricityProducerSolarAsset> persistenceEvent) {
        LOG.info("Processing producer solar asset change: " + persistenceEvent);
        stopProcessing(persistenceEvent.getEntity().getId());

        if (persistenceEvent.getCause() != PersistenceEvent.Cause.DELETE) {
            if (persistenceEvent.getEntity().isIncludeForecastSolarService().orElse(false)
                    && persistenceEvent.getEntity().getLocation().isPresent()) {
                startProcessing(persistenceEvent.getEntity());
            }
        }
    }

    protected void startProcessing(ElectricityProducerSolarAsset electricityProducerSolarAsset) {
        LOG.fine("Starting calculation for producer solar asset: " + electricityProducerSolarAsset);
        calculationFutures.put(electricityProducerSolarAsset.getId(), executorService.scheduleAtFixedRate(() -> {
            processSolarData(electricityProducerSolarAsset);
        }, 0, 1, TimeUnit.HOURS));
    }

    protected void stopProcessing(String electricityProducerSolarAssetId) {
        ScheduledFuture<?> scheduledFuture = calculationFutures.remove(electricityProducerSolarAssetId);
        if (scheduledFuture != null) {
            LOG.fine("Stopping calculation for producer solar asset: " + electricityProducerSolarAssetId);
            scheduledFuture.cancel(false);
        }
    }

    protected void processSolarData(ElectricityProducerSolarAsset electricityProducerSolarAsset) {
        Optional<Double> lat = electricityProducerSolarAsset.getAttribute(Asset.LOCATION).flatMap(attr -> attr.getValue().map(GeoJSONPoint::getY));
        Optional<Double> lon = electricityProducerSolarAsset.getAttribute(Asset.LOCATION).flatMap(attr -> attr.getValue().map(GeoJSONPoint::getX));
        Optional<Integer> pitch = electricityProducerSolarAsset.getPanelPitch();
        Optional<Integer> azimuth = electricityProducerSolarAsset.getPanelAzimuth();
        Optional<Double> kwp = electricityProducerSolarAsset.getPowerExportMax();
        if (lat.isPresent() && lon.isPresent() && pitch.isPresent() && azimuth.isPresent() && kwp.isPresent()) {
            try (Response response = forecastSolarClient
                    .path(String.format("%f/%f/%d/%d/%f", lat.get(), lon.get(), pitch.get(), azimuth.get(), kwp.get()))
                    .request()
                    .build("GET")
                    .invoke()) {
                if (response != null && response.getStatus() == 200) {
                    EstimateResponse responseModel = response.readEntity(EstimateResponse.class);
                    if (responseModel != null) {
                        // Forecast date time is ISO8601 without 'T' so needs special formatter
                        LocalDateTime now = LocalDateTime.ofInstant(Instant.ofEpochMilli(timerService.getCurrentTimeMillis()), ZoneId.systemDefault());
                        boolean setActualValuePower = electricityProducerSolarAsset.isSetActualValueWithForecast().orElse(false);
                        boolean setActualValueForecastPower = true;

                        for (Map.Entry<String, Double> wattItem : responseModel.result.watts.entrySet()) {
                            LocalDateTime timestamp = LocalDateTime.parse(wattItem.getKey(), ISO_LOCAL_DATE_TIME_WITHOUT_T);

                            assetPredictedDatapointService.updateValue(electricityProducerSolarAsset.getId(), ElectricityProducerSolarAsset.POWER_FORECAST.getName(), -wattItem.getValue() / 1000, timestamp);
                            assetPredictedDatapointService.updateValue(electricityProducerSolarAsset.getId(), ElectricityProducerSolarAsset.POWER.getName(), -wattItem.getValue() / 1000, timestamp);

                            if (setActualValueForecastPower && timestamp.isAfter(now)) {
                                assetProcessingService.sendAttributeEvent(new AttributeEvent(electricityProducerSolarAsset.getId(), ElectricityProducerSolarAsset.POWER_FORECAST, -wattItem.getValue() / 1000));
                                setActualValueForecastPower = false;

                                if (setActualValuePower) {
                                    assetProcessingService.sendAttributeEvent(new AttributeEvent(electricityProducerSolarAsset.getId(), ElectricityProducerSolarAsset.POWER, -wattItem.getValue() / 1000));
                                    setActualValuePower = false;
                                }
                            }
                        }
                        rulesService.fireDeploymentsWithPredictedDataForAsset(electricityProducerSolarAsset.getId());
                    }
                } else {
                    LOG.warning("Request failed: " + response);
                }
            } catch (Throwable e) {
                if (e.getCause() != null && e.getCause() instanceof IOException) {
                    LOG.log(Level.SEVERE, "Exception when requesting forecast solar data", e.getCause());
                } else {
                    LOG.log(Level.SEVERE, "Exception when requesting forecast solar data", e);
                }
            }
        } else {
            LOG.warning(String.format("Asset %s doesn't have all needed attributes filled in", electricityProducerSolarAsset.getId()));
        }
    }
}