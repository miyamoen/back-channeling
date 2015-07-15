# Back channeling

## 主な機能

### アカウントを作る

ボット用にはパスワードを入力するのではなく、APIキーが発行されます。
表示されているキーをメモっておいて、API実行時に使用します。

### スレッドを立てる

Newタブを表示すると、新規スレッド作成用のフォームが開きます。
スレッドのタイトルと内容を書いて、Ctrl + Enterを押すとスレッドが新たに作られます。

内容は書いたまま表示される"Plain"と、Github fravored markdown形式の"Markdown"の何れかが選択できます。
どちらの場合もフォームの右側にプレビューが表示されますので、書き込んだ結果の確認が可能です。

### スレッドに書き込む

スレッドのs

## API

APIは、EDN形式です。

### スレッドの一覧を取得する

```
GET /api/board/:board-name
```

```
