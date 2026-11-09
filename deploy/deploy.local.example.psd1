# Copy to deploy.local.psd1 (not in git) and fill in: target of deploy.ps1.
@{
    # Static IP or name of the instance.
    HostName = "203.0.113.10"
    # SSH key downloaded from the Lightsail console; "~" is the home folder.
    KeyPath = "~\.ssh\LightsailDefaultKey-eu-west-3.pem"
    # Public HTTPS address of the server.
    PublicUrl = "https://follow.example.com"
    # Optional: JDK and Gradle cache used to build the server.
    # JavaHome = "C:\path\to\jdk21"
    # GradleUserHome = "C:\path\to\gradle-home"
}
