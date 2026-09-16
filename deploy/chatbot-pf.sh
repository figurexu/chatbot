#!/bin/bash
export KUBECONFIG=/home/figur/.kube/config
kubectl port-forward svc/chatbot -n chatbot 8113:80 --address 127.0.0.1 &
wait
