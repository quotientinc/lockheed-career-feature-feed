/*
 *  Copyright 2015 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.lockheedmartin.aem.core.schedulers;

import com.day.cq.commons.Externalizer;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.ReplicationException;
import com.day.cq.replication.ReplicationStatus;
import com.day.cq.replication.Replicator;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.google.gson.*;
import com.lockheedmartin.aem.core.career.comparators.SortNewsItemByDate;
import com.lockheedmartin.aem.core.career.models.LockheedNewsItem;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.apache.lucene.queries.function.valuesource.MultiFunction;
import org.apache.sling.api.resource.*;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.commons.scheduler.ScheduleOptions;
import org.apache.sling.commons.scheduler.Scheduler;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.jcr.*;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A simple demo for cron-job like tasks that get executed regularly.
 * It also demonstrates how property values can be set. Users can
 * set the property values in /system/console/configMgr
 */
@Designate(ocd=LockheedCareerFeatureFeedScheduler.Config.class)
@Component(service=Runnable.class)
public class LockheedCareerFeatureFeedScheduler implements Runnable {

    @ObjectClassDefinition(name="Lockheed Career Feature Feed Scheduler Configuration",
                           description = "Simple demo for cron-job like task with properties")
    public static @interface Config {

        @AttributeDefinition(name = "Cron-job expression")
        String scheduler_expression() default "0 0 0 * * ?";

        @AttributeDefinition(name = "JSON File Path")
        String json_path() default "/content";
        
        @AttributeDefinition(name = "Mapping File Path")
        String mapping_file_path() default "";        

        @AttributeDefinition(name = "Enable Service")
        boolean is_enabled() default false;

        @AttributeDefinition(name = "Date String", description = "Format YYYYMMDD")
        String get_date_string() default "20190101";

        @AttributeDefinition(name = "Local Root Path", description = "Path to search on for Lockheed-Martin Featured News Stories in AEM")
        String[] get_root_path() default {"/content/lockheed-martin/en-us/career"};     
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private Scheduler scheduler;

    @Reference
    private Replicator replicator;

    private javax.jcr.Session session;
    private ResourceResolver resourceResolver;
    private Config config;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final String JOB_NAME = "Lockheed-Martin Career Feature Feed Job";

    private TreeMap<String, List<String>> tagMap = new TreeMap<String, List<String>>();
    private TreeMap<String, String> tagTitleMap = new TreeMap<String, String>();    
    
    @Override
    public void run() {}

    @Activate
    protected void activate(final Config config)
    {
        this.config = config;

        try
        {
            try
            {
                this.scheduler.unschedule(JOB_NAME);
                logger.info("Removed Job: " + JOB_NAME);
            }
            catch(Exception e)
            {
                logger.info("Error removing Job:" + JOB_NAME + ":" + e.toString());
            }

            final Runnable job = new Runnable()
            {
                public void run() {
                    try
                    {
                        resourceResolver = resolverFactory.getServiceResourceResolver(null);
                        session = resourceResolver.adaptTo(Session.class);

                        Map<String, Object> param = new HashMap<String, Object>();
                        param.put(ResourceResolverFactory.SUBSERVICE, "LockheedCareerFeatureFeedScheduler");

                        if(config.is_enabled())
                        {
                            getMapping();
                            writeNewsfeedJSONToRepo();
                        }
                    }
                    catch (Exception e)
                    {
                        logger.error("Run error: {}", e.toString());
                    }
                    finally
                    {
                        session.logout();
                        if(resourceResolver != null)
                        {
                            resourceResolver.close();
                        }
                    }
                }
            };

            ScheduleOptions scheduler_options = scheduler.EXPR(config.scheduler_expression());
            scheduler_options.name(JOB_NAME);
            this.scheduler.schedule(job, scheduler_options);
        }
        catch(Exception e)
        {
            //e.printStackTrace();
            logger.error(e.getMessage());
        }
    }

    private void writeNewsfeedJSONToRepo() throws Exception {
        String jsonString = getNewsItemsAsJSON();

        Resource metadataOptionJson = ResourceUtil.getOrCreateResource(
                resourceResolver,
                this.config.json_path() + "/careerfeed.json",
                Collections.singletonMap("jcr:primaryType",(Object) "nt:file"),
                null, false);

        Resource metadataOptionJsonJcrContent = ResourceUtil.getOrCreateResource(
                resourceResolver,
                metadataOptionJson.getPath() + "/jcr:content",
                Collections.singletonMap("jcr:primaryType", (Object) "nt:resource"),
                null, false);

        final ModifiableValueMap metadataOptionJsonProperties = metadataOptionJsonJcrContent.adaptTo(ModifiableValueMap.class);

        if (metadataOptionJsonProperties.get("jcr:data") != null)
        {
            metadataOptionJsonProperties.remove("jcr:data");
        }

        metadataOptionJsonProperties.put("jcr:mimeType", "application/json");

        metadataOptionJsonProperties.put("jcr:encoding", "utf-8");
        final ByteArrayInputStream bais = new ByteArrayInputStream(jsonString.getBytes(StandardCharsets.UTF_8));
        metadataOptionJsonProperties.put("jcr:data", bais);

        resourceResolver.commit();
        replicator.replicate(session, ReplicationActionType.ACTIVATE, metadataOptionJson.getPath());
    }

    private String getNewsItemsAsJSON() throws Exception {
        List<LockheedNewsItem> items = new ArrayList<>();

        items.addAll(getAEMNewsfeedPages());
        //items.addAll(readWSJson());
        //items.addAll(getNewswireEntries());

        items.sort(new SortNewsItemByDate());

        Collections.reverse(items);

        Gson gson = new GsonBuilder()
                        .excludeFieldsWithoutExposeAnnotation()
                        .create();

        return gson.toJson(items);
    }

    private List<LockheedNewsItem> getNewswireXML(Element releases)
    {
        List<LockheedNewsItem> items = new ArrayList<>();
        NodeList nl = releases.getElementsByTagName("release");

        try
        {
            for(int i = 0; i < nl.getLength(); i++)
            {
                Element el = (Element) nl.item(i);

                LockheedNewsItem item = parseNewsItem(el);

                if(item != null)
                {
                    items.add(item);
                }
            }
        }
        catch(Exception e)
        {
            //logger.error(e.getMessage());
            //e.printStackTrace();
        }

        return items;
    }

    private List<LockheedNewsItem> getAEMNewsfeedPages()
    {
        List<LockheedNewsItem> items = new ArrayList<>();

        List<Page> pageQueue = new ArrayList<>();

        PageManager pageManager;
        pageManager = resourceResolver.adaptTo(PageManager.class);

        Externalizer ext = resourceResolver.adaptTo(Externalizer.class);
        
        for(String rootPath : config.get_root_path()) {

            Page rootPage = pageManager.getPage(rootPath);

            if(rootPage != null)
            {
                pageQueue.add(rootPage);

                while(pageQueue.size() > 0)
                {
                    Page p = pageQueue.remove(0);

                    Iterator<Page> children = p.listChildren();

                    while(children.hasNext())
                    {
                        pageQueue.add(children.next());
                    }

                    Node pNode = p.adaptTo(Node.class);

                    try
                    {
                        if(pNode.hasNode("jcr:content"))
                        {
                            Node content = pNode.getNode("jcr:content");

                            boolean isPublished = false;

                            ReplicationStatus publishedStatus = null;
                            publishedStatus = p.adaptTo(ReplicationStatus.class);
                            isPublished = publishedStatus.isActivated();

                            boolean isArticle = false;
                            boolean isFeature = false;
                            boolean isPressRelease = false;
                            boolean isStatementSpeech = false;
                            
                            /*
                            if(content.hasProperty("contentTypeTag"))
                            {
                                if(content.getProperty("contentTypeTag").isMultiple())
                                {
                                    List<Value> contentTypeTagValues = Arrays.asList(content.getProperty("contentTypeTag").getValues());

                                    for(int i = 0; i < contentTypeTagValues.size(); i++)
                                    {
                                        if(contentTypeTagValues.get(i).getString().equals("content-type:article"))
                                        {
                                            isArticle = true;
                                        }
                                        if(contentTypeTagValues.get(i).getString().equals("content-type:stories"))
                                        {
                                            isFeature = true;
                                        }
                                        if(contentTypeTagValues.get(i).getString().equals("content-type:press-release"))
                                        {
                                            isPressRelease = true;
                                        }    
                                        if(contentTypeTagValues.get(i).getString().equals("content-type:statement")||contentTypeTagValues.get(i).getString().equals("content-type:speech"))
                                        {
                                            isStatementSpeech = true;
                                        }                                       
                                    }
                                }
                                else
                                {
                                    if(content.getProperty("contentTypeTag").getString().equals("content-type:article"))
                                    {
                                        isArticle = true;
                                    }
                                    if(content.getProperty("contentTypeTag").getString().equals("content-type:stories"))
                                    {
                                        isFeature = true;
                                    }
                                    if(content.getProperty("contentTypeTag").getString().equals("content-type:press-release"))
                                    {
                                        isPressRelease = true;
                                    }  
                                    if(content.getProperty("contentTypeTag").getString().equals("content-type:statement")||content.getProperty("contentTypeTag").getString().equals("content-type:speech"))
                                    {
                                        isStatementSpeech = true;
                                    }                                  
                                }
                            */
                            if(content.hasProperty("cq:template"))
                            {
                                if(content.getProperty("cq:template").isMultiple())
                                {
                                    List<Value> contentTypeTagValues = Arrays.asList(content.getProperty("cq:template").getValues());

                                    for(int i = 0; i < contentTypeTagValues.size(); i++)
                                    {
                                        if(contentTypeTagValues.get(i).getString().equals("/apps/lockheed-martin/templates/2022/careers-article-page"))
                                        {
                                            isArticle = true;
                                        }                                     
                                    }
                                }
                                else
                                {
                                    if(content.getProperty("cq:template").getString().equals("/apps/lockheed-martin/templates/2022/careers-article-page"))
                                    {
                                        isArticle = true;
                                    }                                 
                                }
                            }                       

                            //boolean hasExternalPath = false;

                            //boolean isExternal = isArticle && hasExternalPath;
                            //logger.error("Checking "+p.getPath());
                            if(isPublished && (isArticle))
                            {   //logger.error("Parsing "+p.getPath());
                                /** get the page title **/
                                String storyType = "";

                                if(isArticle) {
                                    storyType = "Article";
                                }                           

                                String title = p.getTitle();

                                /** get page url **/
                                String url = p.getPath() + ".html";

                                if(content.hasProperty("externalNewsArticlePath"))
                                {
                                    if(content.getProperty("externalNewsArticlePath").isMultiple())
                                    {
                                        List<Value> externalPaths = Arrays.asList(content.getProperty("externalNewsArticlePath").getValues());

                                        for(int i = 0; i < externalPaths.size(); i++)
                                        {
                                            if(!externalPaths.get(i).getString().isEmpty())
                                            {
                                                url = externalPaths.get(i).getString();
                                                break;
                                            }
                                        }
                                    }
                                    else
                                    {
                                        url = content.getProperty("externalNewsArticlePath").getString();
                                    }
                                }                            

                                Calendar dateTime = null;

                                /** get the lastModified date **/
                                if(content.hasProperty("dateTime")) {
                                    dateTime = content.getProperty("dateTime").getDate();
                                }
                                else if(content.hasProperty("cq:lastModified"))
                                {
                                    dateTime = content.getProperty("cq:lastModified").getDate();
                                }
                                else if(content.hasProperty("jcr:created"))
                                {
                                    dateTime = content.getProperty("jcr:created").getDate();
                                }
                                else
                                {
                                    dateTime = null;
                                }

                                /** Get thumbnail url for the page **/
                                String thumbnailUrl = "";
                                if(content.hasNode("thumbnailImage"))
                                {
                                    Node thumbnail = content.getNode("thumbnailImage");

                                    if(thumbnail.hasProperty("fileReference"))
                                    {
                                        thumbnailUrl = thumbnail.getProperty("fileReference").getString();
                                    }
                                }

                                /** Get page tags **/
                                TreeMap<String, String> careerPath = getCareerPathTags(content);
                                TreeMap<String, String> category = getCategoryTags(content);

                                items.add(new LockheedNewsItem(title, dateTime, url, thumbnailUrl, careerPath, storyType, category));
                            }
                        }
                    }
                    catch(Exception e)
                    {
                        //logger.error(e.getMessage());
                        //e.printStackTrace();
                    }
                }
            }
        }

        return items;
    }

    private LockheedNewsItem parseNewsItem(Element release)
    {
        try
        {
            String id = release.getElementsByTagName("id").item(0).getTextContent();
            String title = release.getElementsByTagName("headline").item(0).getTextContent();
            String releaseDate = release.getElementsByTagName("releaseDate").item(0).getTextContent();
            String url = release.getElementsByTagName("url").item(0).getTextContent();
            String imageSrc = "";

            List<String> tags = new ArrayList<>();

            NodeList tagElements = release.getElementsByTagName("tag");

            for(int i = 0; i < tagElements.getLength(); i++)
            {
                tags.add(tagElements.item(i).getTextContent());
            }

            if(releaseDate == null)
            {
                return null;
            }

            if(release.getElementsByTagName("image_url").getLength() > 0)
            {
                imageSrc = release.getElementsByTagName("image_url").item(0).getTextContent();
            }

            return new LockheedNewsItem(id, title, releaseDate, url, imageSrc, tags);
        }
        catch(Exception e)
        {
            //logger.error(e.getMessage());
            //e.printStackTrace();
        }

        return null;
    }


    private void getMapping() {
        String mapFilePath = config.mapping_file_path()+"/jcr:content";
        logger.error("Get Mapping for "+mapFilePath);
        try {
            if(session.nodeExists(mapFilePath)) {
                String mapInput = session.getNode(mapFilePath).getProperty("jcr:data").getString();
                JsonParser parser = new JsonParser();
                
                if(parser.parse(mapInput).isJsonObject()) {
                    JsonObject mapData = parser.parse(mapInput).getAsJsonObject();
                    
                    if(mapData.has("Tags")) {
                        JsonObject tagData = mapData.getAsJsonObject("Tags");
                        Object[] tagDataKeys = tagData.keySet().toArray();
                        //logger.error("Found Tags");
                        for(int i=0; i<tagDataKeys.length; i++) {
                            String tagKey = tagDataKeys[i].toString();
                            if(tagData.has(tagKey)) {
                                JsonArray tagValue = tagData.getAsJsonArray(tagKey);

                                List<String> tagValueList = new ArrayList<String>();
                                for(int j=0; j<tagValue.size(); j++) {
                                    JsonElement tagValueElem = tagValue.get(j);
                                    //logger.error("Adding "+tagKey);
                                    tagValueList.add(tagValueElem.getAsString());
                                }
                                tagMap.put(tagKey, tagValueList);
                            }
                        }
                    }
                    
                    if(mapData.has("TagTitle")) {
                        JsonObject tagTitleData = mapData.getAsJsonObject("TagTitle");
                        Object[] tagTitleDataKeys = tagTitleData.keySet().toArray();
                        
                        for(int i=0; i<tagTitleDataKeys.length; i++) {
                            String tagKey = tagTitleDataKeys[i].toString();
 
                            if(tagTitleData.has(tagKey)) {
                                JsonPrimitive tagValue = tagTitleData.getAsJsonPrimitive(tagKey);
                                tagTitleMap.put(tagKey, tagValue.getAsString());
                            }
                        }                        
                    }

                }
            }
        } catch(Exception e) {
            logger.error(e.getMessage());
            e.printStackTrace();            
        }
        
    }
 

    private TreeMap<String, String> getCareerPathTags(Node content)
    {
        //List<String> tags = new ArrayList<>();
        TreeMap<String, String> tags = new TreeMap<String, String>();

        try
        {
            TagManager tm = resourceResolver.adaptTo(TagManager.class);
            List<Value> tagValues = new ArrayList<>();



            if(content.hasProperty("careerPathTag"))
            {
                if(content.getProperty("careerPathTag").isMultiple())
                {
                    tagValues.addAll(Arrays.asList(content.getProperty("careerPathTag").getValues()));
                }
                else
                {
                    tagValues.add(content.getProperty("careerPathTag").getValue());
                }
            }

            for(Value v: tagValues)
            {
                String tagId = v.getString();
                Tag t = tm.resolve(tagId);
                tags.put(t.getName(), t.getTitle());
            }

            //Collections.sort(tags);
        }
        catch(Exception e)
        {
            //e.printStackTrace();
        }

        return tags;
    }    
    
    private TreeMap<String, String> getCategoryTags(Node content)
    {
        //List<String> tags = new ArrayList<>();
        TreeMap<String, String> tags = new TreeMap<String, String>();

        try
        {
            TagManager tm = resourceResolver.adaptTo(TagManager.class);
            List<Value> tagValues = new ArrayList<>();



            if(content.hasProperty("careerCategory"))
            {
                if(content.getProperty("careerCategory").isMultiple())
                {
                    tagValues.addAll(Arrays.asList(content.getProperty("careerCategory").getValues()));
                }
                else
                {
                    tagValues.add(content.getProperty("careerCategory").getValue());
                }
            }

            for(Value v: tagValues)
            {
                String tagId = v.getString();
                Tag t = tm.resolve(tagId);
                tags.put(t.getName(), t.getTitle());
            }

            //Collections.sort(tags);
        }
        catch(Exception e)
        {
            //e.printStackTrace();
        }

        return tags;
    }

      
}
