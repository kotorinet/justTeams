package eu.kotori.justTeams.api;

public interface ClanCustomDataCodec<T> {
    /**
     * Get the class type this codec handles.
     * 
     * @return The class type
     */
    Class<T> getType();

    /**
     * Serialize the object to a String.
     * 
     * @param obj The object to serialize
     * @return The serialized string
     */
    String serialize(T obj);

    /**
     * Deserialize the String back to an object.
     * 
     * @param data The string data
     * @return The deserialized object
     */
    T deserialize(String data);
}
