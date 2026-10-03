package com.collabeditor.realtime_editor.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;

@Configuration
public class MongoConfig implements InitializingBean {

    @Autowired
    private MappingMongoConverter mappingMongoConverter;

    /**
     * Replaces dots in MongoDB map keys with "_dot_".
     * Filenames like "main.js" are used as map keys when saving snapshots,
     * and MongoDB forbids dots in field names.
     * This modifies the auto-configured converter rather than replacing it.
     */
    @Override
    public void afterPropertiesSet() {
        mappingMongoConverter.setMapKeyDotReplacement("_dot_");
    }
}