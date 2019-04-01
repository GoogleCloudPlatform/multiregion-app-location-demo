<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <title>Where Am I?</title>
    <link rel="stylesheet" href="/assets/index.css">
</head>
<body>
    <h2>hello, from ${geo.searchString()}!</h2>

    <p id="ping">Calculating round trip time...</p>

    <#if imgUrl??>
        <img src="${imgUrl}">
    <#else>
        Could not get an image for that location.
    </#if>

    <script>
        var t0 = Date.now();

        var req = new XMLHttpRequest();
        req.addEventListener("load", function() {
          var t1 = Date.now();

          var elapsed = (t1 - t0);

          document.getElementById("ping").innerText = "Ping took " + elapsed + "ms";
        });
        req.open("GET", "/ping?" + Math.random());
        req.send();
    </script>
</body>
</html>
