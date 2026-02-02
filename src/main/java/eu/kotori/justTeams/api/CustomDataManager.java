package eu.kotori.justTeams.api;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class CustomDataManager {
    private final Map<Class<?>, ClanCustomDataCodec<?>> codecs = new HashMap<>();
    private final Logger logger;

    public CustomDataManager(Logger logger) {
        this.logger = logger;
        registerDefaultCodecs();
    }

    private void registerDefaultCodecs() {

        registerCodec(new ClanCustomDataCodec<String>() {
            @Override
            public Class<String> getType() {
                return String.class;
            }

            @Override
            public String serialize(String obj) {
                return obj;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        });

        registerCodec(new ClanCustomDataCodec<Integer>() {
            @Override
            public Class<Integer> getType() {
                return Integer.class;
            }

            @Override
            public String serialize(Integer obj) {
                return String.valueOf(obj);
            }

            @Override
            public Integer deserialize(String data) {
                try {
                    return Integer.parseInt(data);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        });

        registerCodec(new ClanCustomDataCodec<Boolean>() {
            @Override
            public Class<Boolean> getType() {
                return Boolean.class;
            }

            @Override
            public String serialize(Boolean obj) {
                return String.valueOf(obj);
            }

            @Override
            public Boolean deserialize(String data) {
                return Boolean.parseBoolean(data);
            }
        });
    }

    public <T> void registerCodec(ClanCustomDataCodec<T> codec) {
        if (codecs.containsKey(codec.getType())) {
            logger.warning("Overwriting existing codec for type: " + codec.getType().getName());
        }
        codecs.put(codec.getType(), codec);
    }

    @SuppressWarnings("unchecked")
    public <T> ClanCustomDataCodec<T> getCodec(Class<T> type) {
        return (ClanCustomDataCodec<T>) codecs.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T> String serialize(T obj) {
        if (obj == null)
            return null;

        ClanCustomDataCodec<T> codec = (ClanCustomDataCodec<T>) getCodec((Class<T>) obj.getClass());
        if (codec == null) {
            throw new IllegalArgumentException("No codec registered for type: " + obj.getClass().getName());
        }
        return codec.serialize(obj);
    }
}
