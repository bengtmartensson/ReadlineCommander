#!/bin/sh

# JAVA VM to use
JAVA=java

# Where libJavaReadline.so (https://github.com/bengtmartensson/java-readline)
# is located
LIBDIR=/usr/lib64/rxtx:/usr/local/lib64

# Full pathname of jar
JAR=/usr/local/share/java/ReadlineCommander-jar-with-dependencies.jar

${JAVA} -Djava.library.path=${LIBDIR} -jar ${JAR}  "$@"
