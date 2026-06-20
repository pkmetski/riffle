package android.net;

/**
 * Concrete {@link Uri} subclass for use in JVM unit tests.
 *
 * {@link Uri} has a package-private constructor, so it can only be subclassed from within
 * {@code android.net}. Placing this file under {@code src/test/java/android/net/} lets it
 * participate in the package while remaining test-only.
 *
 * Only {@link #toString()} is meaningful — all other abstract methods throw
 * {@link UnsupportedOperationException} so tests fail loudly if they accidentally invoke them.
 */
public class FakeUri extends Uri {

    private final String uriString;

    public FakeUri(String uriString) {
        this.uriString = uriString;
    }

    @Override
    public String toString() {
        return uriString;
    }

    @Override
    public boolean isHierarchical() {
        return true;
    }

    @Override
    public boolean isRelative() {
        return false;
    }

    @Override
    public String getScheme() {
        int colon = uriString.indexOf(':');
        return colon < 0 ? null : uriString.substring(0, colon);
    }

    @Override
    public String getSchemeSpecificPart() {
        throw new UnsupportedOperationException("FakeUri stub");
    }

    @Override
    public String getEncodedSchemeSpecificPart() {
        throw new UnsupportedOperationException("FakeUri stub");
    }

    @Override
    public String getAuthority() {
        return null;
    }

    @Override
    public String getEncodedAuthority() {
        return null;
    }

    @Override
    public String getUserInfo() {
        return null;
    }

    @Override
    public String getEncodedUserInfo() {
        return null;
    }

    @Override
    public String getHost() {
        return null;
    }

    @Override
    public int getPort() {
        return -1;
    }

    @Override
    public String getPath() {
        throw new UnsupportedOperationException("FakeUri stub");
    }

    @Override
    public String getEncodedPath() {
        throw new UnsupportedOperationException("FakeUri stub");
    }

    @Override
    public java.util.List<String> getPathSegments() {
        return java.util.Collections.emptyList();
    }

    @Override
    public String getLastPathSegment() {
        int slash = uriString.lastIndexOf('/');
        return slash < 0 ? uriString : uriString.substring(slash + 1);
    }

    @Override
    public String getQuery() {
        return null;
    }

    @Override
    public String getEncodedQuery() {
        return null;
    }

    @Override
    public String getFragment() {
        return null;
    }

    @Override
    public String getEncodedFragment() {
        return null;
    }

    @Override
    public Uri.Builder buildUpon() {
        throw new UnsupportedOperationException("FakeUri stub");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(android.os.Parcel dest, int flags) {
        throw new UnsupportedOperationException("FakeUri stub");
    }
}
