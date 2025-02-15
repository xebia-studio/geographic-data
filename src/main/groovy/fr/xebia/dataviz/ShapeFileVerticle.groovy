package fr.xebia.dataviz

import com.vividsolutions.jts.geom.Geometry
import org.elasticsearch.action.index.IndexResponse
import org.elasticsearch.client.Client
import org.elasticsearch.client.transport.TransportClient
import org.elasticsearch.common.settings.ImmutableSettings
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.transport.InetSocketTransportAddress
import org.geotools.data.FeatureSource
import org.geotools.data.FileDataStore
import org.geotools.data.FileDataStoreFinder
import org.geotools.feature.FeatureCollection
import org.geotools.feature.FeatureIterator
import org.opengis.feature.simple.SimpleFeature
import org.vertx.groovy.platform.Verticle
import org.vertx.java.core.file.impl.PathAdjuster
import org.vertx.java.core.impl.VertxInternal
import org.vertx.java.core.json.impl.Json

import java.util.concurrent.ConcurrentHashMap

/**
 * User: mounirboudraa
 * Date: 13/12/2013
 * Time: 17:25
 */
class ShapeFileVerticle extends Verticle {

    int count = 0
    int total = 0
    int totalOk = 0

    def map = [:] as ConcurrentHashMap
    Client client

    def loadCities() {

        findOnDisk("villes_france.csv").splitEachLine(",") { fields ->
            String key = String.valueOf(fields[9]).trim()
            map.put(key, [
                    code: key,
                    formattedName: fields[3],
                    name: fields[4],
                    postcode: fields[7],
                    population: fields[13],
                    density: fields[16],
                    surface: fields[17],
                    lat: fields[19],
                    lng: fields[18],
                    altitudeMin: fields[24],
                    altitudeMax: fields[25],
                    populationRank: fields[26],
                    densityRank: fields[27],
                    surfaceRank: fields[28],
                    countryCode: "fr",
            ])

        }


    }

    def start() {
        println "CREATE ES CLIENT"
        // Mode pur batch
        def host = container.config.host ?: 'localhost'
        def port = container.config.port ?: 9300
        def cluster = container.config.cluster ?:'unibail-fid'

        Settings settings = ImmutableSettings.settingsBuilder().put("cluster.name", cluster).build()
        client = new TransportClient(settings)
                .addTransportAddress(new InetSocketTransportAddress(host, port))



        print "LOADING CITIES"
        loadCities()
        println " -> DONE"





        vertx.fileSystem.readDir(findOnDisk("osm").getAbsolutePath(), ".*\\.shp") { ar ->
            if (ar.succeeded) {
                for (fileName in ar.result) {
                    File folder = new File(fileName)
                    if (folder.getName().substring(0, 2).equals("2A") || folder.getName().substring(0, 2).equals("2B") || Integer.valueOf(folder.getName().substring(0, 2)) >= container.config.startIndex) {
                        FileDataStore store;
                        try {
                            store = FileDataStoreFinder.getDataStore(findOnDisk("osm/${folder.getName()}/${folder.getName()}"))

                            FeatureSource featureSource = store.getFeatureSource()
                            FeatureCollection featureCollection = featureSource.getFeatures()

                            FeatureIterator featureIterator = featureCollection.features()

                            try {
                                while (featureIterator.hasNext()) {
                                    total++
                                    SimpleFeature feature = (SimpleFeature) featureIterator.next();


                                    String cityCode = ((String) feature.getAttributes().get(2)).trim();

                                    if (map.get(cityCode)) {
                                        def city = map.get(cityCode).clone()
                                        map.remove(cityCode)

                                        Geometry geometry = (Geometry) feature.getAttributes().get(0);

                                        def coords = []

                                        geometry.coordinates.eachWithIndex() { p, i ->
                                            def coord = []
                                            coord[0] = p.x
                                            coord[1] = p.y

                                            coords[i] = coord
                                        }

                                        city.put('boundaries', coords)
                                        count++
                                        //println count
                                        sendToElasticSearch(city)
                                    }


                                }

                            } finally {
                                featureIterator.close()
                                store.dispose()
                                println "Data of ${folder.getName()} processed"
                            }

                        } catch (Exception e) {
                            e.printStackTrace()
                            println "Error on => ${folder.getName()} => ${e.message}"
                        }
                    }
                }



                println("SUCCESS => ${count}/${total} cities sent")

            } else {
                println "Failed to read => ${ar.cause}"
            }
        }


    }

    def stop() {
        client?.close()
    }


    private void sendToElasticSearch(city) {

        Map message = [
                "index": "cities",
                "entity": "ville_fr",
                "id": city.code,
                "content": city
        ]

//        println "sending -> ${message.content.postcode} : ${message.content.name}"

        //vertx.eventBus.send("fr.xebia.dataviz.es.createObject", message) { response ->
        //    retry(response, message)
        //}

        /*def put = esClient.put("/$message.index/$message.entity/$message.id") { esResp ->
            //def put = esClient.post("/$index/$entity/") { esResp ->
            if(esResp.statusCode!=200 && esResp.statusCode!=201 ){
//                println "Error indexing in es : ${esResp.statusCode} for ${id} : ${content.formattedName}"
            }
            def body = new Buffer()
            esResp.dataHandler { buffer -> body << buffer }
            esResp.endHandler {
                println "statusCode: ${esResp.statusCode} cityCode : ${city.code}"
            }
        }

        //put.putHeader("Content-Encoding", "gzip")
        put.putHeader("Accept-Encoding", "compress, gzip")
        put.chunked = true
        put << Json.encode(city)
        put.end()*/
        IndexResponse response = client.prepareIndex("cities", "ville_fr", city.code)
                .setSource(Json.encode(city))
                .execute()
                .actionGet()


    }

    private void retry(response, message) {
        if (response.body.statusCode > 201) {
//            println "retrying -> ${message.content.postcode} : ${message.content.name}"

            vertx.eventBus.send("fr.xebia.dataviz.es.createObject", message) { resp ->
                retry(resp, message)
            }
        } else {
            totalOk++
            println "${totalOk}/${count} indexed"
        }
    }

    private File findOnDisk(String resourceRelativePath) {
        VertxInternal core = vertx.toJavaVertx() as VertxInternal
        String pathToDisk = PathAdjuster.adjust(core, resourceRelativePath)
        new File(pathToDisk)
    }

}
