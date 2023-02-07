git pull && chmod 777 gradlew && ./gradlew shadowJar && mkdir -p ~/logs/lambda/video_compress && aws s3 cp build/libs/AwsVideoCompress-1.0-all.jar s3://downappssr-southeast/lambda/video_compress/lambda.jar  && aws lambda update-function-code --function-name ssr_video_compress_s3 --s3-bucket downappssr-southeast --s3-key lambda/video_compress/lambda.jar >> ~/logs/lambda/video_compress/s3.log && aws lambda update-function-code --function-name ssr_video_compress_call --s3-bucket downappssr-southeast --s3-key lambda/video_compress/lambda.jar >> ~/logs/lambda/video_compress/call.log