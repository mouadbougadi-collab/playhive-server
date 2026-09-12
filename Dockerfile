FROM eclipse-temurin:17-jdk

WORKDIR /app

# تحميل المكتبات المطلوبة لإرسال البريد
RUN apt-get update && apt-get install -y wget && \
    wget -q https://repo1.maven.org/maven2/com/sun/mail/javax.mail/1.6.2/javax.mail-1.6.2.jar && \
    wget -q https://repo1.maven.org/maven2/com/sun/activation/javax.activation/1.2.0/javax.activation-1.2.0.jar

COPY Server.java .

RUN javac -cp .:javax.mail-1.6.2.jar:javax.activation-1.2.0.jar Server.java

CMD ["java", "-cp", ".:javax.mail-1.6.2.jar:javax.activation-1.2.0.jar", "Server"]
