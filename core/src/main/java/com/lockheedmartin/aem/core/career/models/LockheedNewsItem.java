package com.lockheedmartin.aem.core.career.models;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TreeMap;

public class LockheedNewsItem
{
    @Expose @SerializedName(value = "ID")
    String id;

    @Expose @SerializedName(value = "Title")
    String title;

    @Expose @SerializedName(value = "Date")
    String date;

    @Expose @SerializedName(value = "URL")
    String url;

    @Expose @SerializedName(value = "Thumbnail Image")
    String thumbnailUrl;

    @Expose @SerializedName(value = "Career Path")
    TreeMap<String, String> careerPath;

    @Expose @SerializedName(value = "Story Type")
    String storytype;
    
    @Expose @SerializedName(value = "Category")
    TreeMap<String, String> category;    

    final String datePattern = "EEE, d MMM yyyy HH:mm:ss z";

    public LockheedNewsItem(String id, String title, String date, String url, String thumbnailUrl, List<String> tags)
    {
        /*
        this.id = id;
        this.title = title;
        this.date = date;
        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.storytype = "release";

        this.tags = tags;
        */
    }

    public LockheedNewsItem(String title, Calendar dateTime, String url, String thumbnailUrl, TreeMap<String, String> careerPath, String type, TreeMap<String, String> category)
    {
        this.title = title;

        if(dateTime != null)
        {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(datePattern);
            this.date = simpleDateFormat.format(dateTime.getTime());
        }
        else
        {
            this.date = "";
        }

        this.url = url;
        this.thumbnailUrl = thumbnailUrl;
        this.careerPath = careerPath;
        this.category = category;

        this.storytype = type;
    }

    public Calendar getCalendarDate() throws ParseException
    {
        if(this.date.equals(""))
        {
            return null;
        }

        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(datePattern);

        Calendar cd = Calendar.getInstance();
        cd.setTime(simpleDateFormat.parse(this.date));
        return cd;
    }
}
