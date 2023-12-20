
Description

This code defines a Peer class which is a basic implementation of a peer-to-peer (P2P) network, where each node (peer) acts both as a server and a client. In the given code, a peer is responsible for serving its own files and searching for files in other peers.

The peer has a name, port, a server socket for accepting incoming connections, a list of active connections, a list of known neighbours, a hash map for pending searches, and a list of files it hosts. When initialized, a peer sets up a server socket and starts watching a specified directory for file changes. Any changes to the files in the directory are registered and reflected in the peer's hosted files list. A peer is also capable of starting and running a server that listens for incoming connections. When a connection is accepted, it spawns a new thread to handle the connection and adds it to the active connections list.

For file searching, it first checks locally, if the file isn't present, it sends a search request to all its connections. The reply is then handled accordingly, it's either stored if it's a reply to a self initiated search or forwarded to the initiator if it's a reply to a forwarded search. In addition to this, a peer is capable of connecting to other peers, shutting down its server and connections, and handling incoming messages. Each connection is managed by a separate Connection class instance that runs on its own thread.

The code also includes the ability to download a requested file from another peer. This is done using a socket connection between the client (downloader) and the server (uploader). The file's content is read and written into the socket's output stream, sent over the network, and written into a new file on the client's side. Overall, this code provides a simple yet complete demonstration of a basic P2P network where peers can search, serve, and download files from each other.

Understanding the Code
1. The primary class in the code is the Peer class. Each Peer object has a name, port number, a server socket, a list of active connections, a list of known neighbors, a hash map for pending searches, and a list of files it hosts.
2. When initialized, a peer starts watching a specified directory for file changes using a watch service from the Java NIO package. Any changes to the files in this directory are registered and reflected in the peer's hosted files list.
3. A Peer can start and run a server that listens for incoming connections. When a connection is accepted, a new thread is spawned to handle the connection and the connection is added to the active connections list.
4. For file searching, the Peer first checks locally. If the file isn't present, it sends a search request to all its connections. The reply is then handled accordingly. If the reply is to a self-initiated search, it is stored, and if the reply is to a forwarded search, the reply is forwarded to the initiator.
5. In addition to this, a Peer is capable of connecting to other peers, shutting down its server and connections, and handling incoming messages. Each connection is managed by a separate Connection class instance running on its own thread.
6. The code also supports downloading of requested files from other peers. This is done using a socket connection between the client (the downloader) and the server (the uploader). The file's content is read and written into the socket's output stream, sent over the network, and written into a new file on the client's side.




