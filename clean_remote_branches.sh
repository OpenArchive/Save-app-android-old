#!/bin/sh

branches=`git for-each-ref --format="%(refname:lstrip=-2)"`

for branch in $branches
do
	read -p "Delete $branch [y/N]? " deleteBranch

	if [[ $branch =~ "origin" ]]; then
		ref=${branch##*/}
	else 
 		ref=${branch}
	fi

	if [ "${deleteBranch}" == "y" ]; then
 		git push origin -d ${ref}
 	fi
done
