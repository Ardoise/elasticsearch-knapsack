/*
 * Copyright (C) 2014 Jörg Prante
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.xbib.elasticsearch.action.knapsack.exp;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;
import org.xbib.elasticsearch.knapsack.KnapsackService;
import org.xbib.elasticsearch.knapsack.KnapsackState;
import org.xbib.io.BytesProgressWatcher;
import org.xbib.io.Session;
import org.xbib.io.archive.ArchivePacket;
import org.xbib.io.archive.ArchiveService;
import org.xbib.io.archive.ArchiveSession;
import org.xbib.io.archive.esbulk.EsBulkSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.client.Requests.createIndexRequest;
import static org.elasticsearch.common.collect.Maps.newHashMap;
import static org.elasticsearch.common.collect.Sets.newHashSet;
import static org.xbib.elasticsearch.knapsack.KnapsackHelper.getAliases;
import static org.xbib.elasticsearch.knapsack.KnapsackHelper.getMapping;
import static org.xbib.elasticsearch.knapsack.KnapsackHelper.getSettings;
import static org.xbib.elasticsearch.knapsack.KnapsackHelper.mapIndex;
import static org.xbib.elasticsearch.knapsack.KnapsackHelper.mapType;

public class TransportKnapsackExportAction extends TransportAction<KnapsackExportRequest, KnapsackExportResponse> {

    private final static ESLogger logger = ESLoggerFactory.getLogger(KnapsackExportAction.class.getSimpleName());

    private final SettingsFilter settingsFilter;

    private final Client client;

    private final NodeService nodeService;

    private final KnapsackService knapsack;

    @Inject
    public TransportKnapsackExportAction(Settings settings,
                                         ThreadPool threadPool, SettingsFilter settingsFilter,
                                         Client client, NodeService nodeService, ActionFilters actionFilters,
                                         KnapsackService knapsack) {
        super(settings, KnapsackExportAction.NAME, threadPool, actionFilters);
        this.settingsFilter = settingsFilter;
        this.client = client;
        this.nodeService = nodeService;
        this.knapsack = knapsack;
    }

    @Override
    protected void doExecute(final KnapsackExportRequest request, ActionListener<KnapsackExportResponse> listener) {
        final KnapsackState state = new KnapsackState()
                .setMode("export")
                .setNodeName(nodeService.nodeName());
        final KnapsackExportResponse response = new KnapsackExportResponse()
                .setState(state);
        try {
            Path path = request.getPath();
            if (path == null) {
                path = new File("_all.tar.gz").toPath();
            }
            ByteSizeValue bytesToTransfer = request.getBytesToTransfer();
            BytesProgressWatcher watcher = new BytesProgressWatcher(bytesToTransfer.bytes());
            final ArchiveSession session = ArchiveService.newSession(path, watcher);
            EnumSet<Session.Mode> mode = EnumSet.of(request.isOverwriteAllowed() ? Session.Mode.OVERWRITE : Session.Mode.WRITE,
                    request.isEncodeEntry() ? Session.Mode.URI_ENCODED : Session.Mode.NONE);
            session.open(mode, path, path.toFile());
            if (session.isOpen()) {
                state.setPath(path).setTimestamp(new DateTime());
                response.setRunning(true);
                knapsack.submit(new Thread() {
                    public void run() {
                        performExport(request, state, session);
                    }
                });
                // ensure to add export to state before response is sent
                knapsack.addExport(client, state);
            } else {
                response.setRunning(false).setReason("session can not be opened: mode=" + mode + " path=" + path);
            }
            listener.onResponse(response);
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
            listener.onFailure(e);
        }
    }

    /**
     * Export thread
     *
     * @param request request
     * @param state   state
     * @param session session
     */
    final void performExport(final KnapsackExportRequest request,
                             final KnapsackState state,
                             final ArchiveSession session) {
        try {
            logger.info("start of export: {}", state);
            Map<String, Set<String>> indices = newHashMap();
            for (String s : Strings.commaDelimitedListToSet(request.getIndex())) {
                indices.put(s, Strings.commaDelimitedListToSet(request.getType()));
            }
            // never write _settings / _mapping to bulk format
            if (request.isWithMetadata() && !(session instanceof EsBulkSession)) {
                if (request.getIndexTypeNames() != null) {
                    for (Object spec : request.getIndexTypeNames().keySet()) {
                        if (spec == null) {
                            continue;
                        }
                        String[] s = spec.toString().split("/");
                        String index = s[0];
                        String type = s.length > 1 ? s[1] : null;
                        if (!"_all".equals(index)) {
                            Set<String> types = indices.get(index);
                            if (types == null) {
                                types = newHashSet();
                            }
                            if (type != null) {
                                types.add(type);
                            }
                            indices.put(index, types);
                        }
                    }
                }
                // get settings for all indices
                logger.info("getting settings for indices {}", indices.keySet());
                Set<String> settingsIndices = newHashSet(indices.keySet());
                settingsIndices.remove("_all");
                Map<String, String> settings = getSettings(client, settingsFilter, settingsIndices.toArray(new String[settingsIndices.size()]));
                logger.info("found indices: {}", settings.keySet());
                // we resolved the specs in indices to the real indices in the settings
                // get mapping and alias per index and create index if copy mode is enabled
                for (String index : settings.keySet()) {
                    CreateIndexRequest createIndexRequest = createIndexRequest(mapIndex(request, index));
                    ArchivePacket packet = new ArchivePacket();
                    packet.meta("index", mapIndex(request, index));
                    packet.meta("type", "_settings");
                    packet.payload(settings.get(index));
                    session.write(packet);
                    Set<String> types = indices.get(index);
                    createIndexRequest.settings(settings.get(index));
                    logger.info("getting mappings for index {} and types {}", index, types);
                    Map<String, String> mappings = getMapping(client, index, types != null ? ImmutableSet.copyOf(types) : null);
                    logger.info("found mappings: {}", mappings.keySet());
                    for (String type : mappings.keySet()) {
                        packet = new ArchivePacket();
                        packet.meta("index", mapIndex(request, index));
                        packet.meta("type", mapType(request, index, type));
                        packet.meta("id", "_mapping");
                        packet.payload(mappings.get(type));
                        session.write(packet);
                        logger.info("adding mapping: {}", mapType(request, index, type));
                        createIndexRequest.mapping(mapType(request, index, type), mappings.get(type));
                    }
                    if (request.isWithAliases()) {
                        logger.info("getting aliases for index {}", index);
                        Map<String, String> aliases = getAliases(client, index);
                        logger.info("found {} aliases", aliases.size());
                        for (String alias : aliases.keySet()) {
                            packet = new ArchivePacket();
                            packet.meta("index", mapIndex(request, index));
                            packet.meta("type", alias);
                            packet.meta("id", "_alias");
                            packet.payload(aliases.get(alias));
                            session.write(packet);
                        }
                    }
                }
            }
            SearchRequest searchRequest = request.getSearchRequest();
            if (searchRequest == null) {
                searchRequest = new SearchRequestBuilder(client).setQuery(QueryBuilders.matchAllQuery()).request();
            }
            for (String index : indices.keySet()) {
                searchRequest.searchType(SearchType.SCAN).scroll(request.getTimeout());
                if (!"_all".equals(index)) {
                    searchRequest.indices(index);
                }
                Set<String> types = indices.get(index);
                if (types != null) {
                    searchRequest.types(types.toArray(new String[types.size()]));
                }
                // use local node client here
                SearchResponse searchResponse = client.search(searchRequest).actionGet();
                long total = 0L;
                while (searchResponse.getScrollId() != null && !Thread.interrupted()) {
                    searchResponse = client.prepareSearchScroll(searchResponse.getScrollId())
                            .setScroll(request.getTimeout())
                            .execute()
                            .actionGet();
                    long hits = searchResponse.getHits().getHits().length;
                    if (hits == 0) {
                        break;
                    }
                    total += hits;
                    logger.debug("total={} hits={} took={}", total, hits, searchResponse.getTookInMillis());
                    for (SearchHit hit : searchResponse.getHits()) {
                        for (String f : hit.getFields().keySet()) {
                            ArchivePacket packet = new ArchivePacket();
                            packet.meta("index", mapIndex(request, hit.getIndex()));
                            packet.meta("type", mapType(request, hit.getIndex(), hit.getType()));
                            packet.meta("id", hit.getId());
                            packet.meta("field", f);
                            packet.payload(hit.getFields().get(f).getValue().toString());
                            session.write(packet);
                        }
                        if (!hit.getFields().keySet().contains("_source")) {
                            ArchivePacket packet = new ArchivePacket();
                            packet.meta("index", mapIndex(request, hit.getIndex()));
                            packet.meta("type", mapType(request, hit.getIndex(), hit.getType()));
                            packet.meta("id", hit.getId());
                            packet.meta("field", "_source");
                            packet.payload(hit.getSourceAsString());
                            session.write(packet);
                        }
                    }
                }
            }
            session.close();
            logger.info("end of export: {}, packets = {}, total bytes transferred = {}, rate = {}",
                    state, session.getPacketCounter(),
                    session.getWatcher().getTotalBytesInAllTransfers(),
                    String.format("%f", session.getWatcher().getRecentByteRatePerSecond()));
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                knapsack.removeExport(client, state);
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

}
