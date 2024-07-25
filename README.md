# 简介

由于经常遇到存储桶遍历漏洞，直接访问文件是下载，不方便预览，且甲方要求证明该存储桶的危害，，因此该工具应运而生。

# 更新日志
## 2024.07.25 v1.1版本

1.新增选择文件查看功能，通过treeview和treeitem实现；
<https://github.com/jdr2021/OSSFileBrowse/issues/3>

2.修改允许后缀显示的逻辑、allow.extensions值是null时，可加载所有文件。

## 2024.05.24 v1.0版本
1.加载存储桶上所有资源，通过按钮去查看文件

2.可自定义`kkfileview`的地址、可指定查看的文件后缀值`allow.extensions`

# 技术
使用`javafx`做图形化，`kkFileView`做文件预览接口。

# 使用
命令行运行`java -Dfile.encoding=UTF-8 -jar OSSFileBrowse-1.0-SNAPSHOT.jar`、或者直接点击`run.bat`文件。
直接运行，如果存储桶上有中文，则会提示漏洞不存在。

<img src="./images/1.png">

先点击`加载`按钮，此时会爬取存储桶上的全部资源，等待几秒后，左边的`webView`将会通过`kkFilewView`去渲染文件资源。

<img src="./images/2.png">

按钮`下一个`将会切换到下一个存储桶资源、按钮`上一个`将会返回到上一个资源。


# 注意
在`config.properties`中

修改`allow.extensions`的值，即可添加可支持的文件类型进行预览。

修改`kkFileView_URL`的值，即可将`kkFileView`修改成自己的kkfileview。
默认是使用官方的`kkfileview`地址。

<img src="images/3.png">


# 最后

如果该项目对你有帮助，给一个小小的`star`吧。
