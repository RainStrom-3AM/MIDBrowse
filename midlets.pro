-injars  dist/MIDBrowse_midlet.jar
-outjars dist/MIDBrowse.jar

-libraryjars lib/cldc_1.1.jar
-libraryjars lib/midp_2.0.jar

-microedition

-keep public class * extends javax.microedition.midlet.MIDlet

-dontoptimize

-repackageclasses ''
-dontusemixedcaseclassnames
-allowaccessmodification
-printmapping out.map
-dontnote
-dontwarn
