package com.lockheedmartin.aem.core.career.comparators;

import com.lockheedmartin.aem.core.career.models.LockheedNewsItem;

import java.util.Comparator;

public class SortNewsItemByDate implements Comparator<LockheedNewsItem>
{

    @Override
    public int compare(LockheedNewsItem i1, LockheedNewsItem i2)
    {
        int comparison = 0;

        try
        {
            if(i1.getCalendarDate() == null && i2.getCalendarDate() == null)
            {
                return 0;
            }
            else if(i1.getCalendarDate() == null && i2.getCalendarDate() != null)
            {
                return -1;
            }
            else if(i1.getCalendarDate() != null && i2.getCalendarDate() == null)
            {
                return 1;
            }

            comparison = i1.getCalendarDate().compareTo(i2.getCalendarDate());
        }
        catch(Exception e)
        {
            //e.printStackTrace();
        }

        return comparison;
    }
}
