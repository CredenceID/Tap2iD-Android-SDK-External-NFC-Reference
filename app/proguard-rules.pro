# The Tap2iD SDK brings its own consumer rules. Nothing is needed here for the SDK or for an adapter:
# the SDK reaches your ContactlessReader through the interface, not by name.

# Transitive dependencies of the SDK that are not present on Android.
-dontwarn com.google.firebase.messaging.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
